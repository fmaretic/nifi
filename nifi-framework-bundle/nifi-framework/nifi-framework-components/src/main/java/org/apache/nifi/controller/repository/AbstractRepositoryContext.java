/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.controller.repository;

import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.ConnectableType;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.provenance.InternalProvenanceReporter;
import org.apache.nifi.provenance.ProvenanceEventBuilder;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventRepository;
import org.apache.nifi.provenance.StandardProvenanceEventRecord;
import org.apache.nifi.util.Connectables;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public abstract class AbstractRepositoryContext implements RepositoryContext {
    private final Connectable connectable;
    private final ContentRepository contentRepo;
    private final FlowFileRepository flowFileRepo;
    private final FlowFileEventRepository flowFileEventRepo;
    private final CounterRepository counterRepo;
    private final ProvenanceEventRepository provenanceRepo;
    private final AtomicLong connectionIndex;
    private final StateManager stateManager;

    public AbstractRepositoryContext(final Connectable connectable, final AtomicLong connectionIndex, final ContentRepository contentRepository,
                                     final FlowFileRepository flowFileRepository, final FlowFileEventRepository flowFileEventRepository,
                                     final CounterRepository counterRepository, final ProvenanceEventRepository provenanceRepository,
                                     final StateManager stateManager) {
        this.connectable = connectable;
        contentRepo = contentRepository;
        flowFileRepo = flowFileRepository;
        flowFileEventRepo = flowFileEventRepository;
        counterRepo = counterRepository;
        provenanceRepo = provenanceRepository;

        this.connectionIndex = connectionIndex;
        this.stateManager = stateManager;
    }

    @Override
    public Connectable getConnectable() {
        return connectable;
    }

    /**
     *
     * @param relationship relationship
     * @return connections for relationship
     */
    @Override
    public Collection<Connection> getConnections(final Relationship relationship) {
        Collection<Connection> collection = connectable.getConnections(relationship);
        if (collection == null) {
            collection = new ArrayList<>();
        }
        return Collections.unmodifiableCollection(collection);
    }

    /**
     * @return an unmodifiable list containing a copy of all incoming connections for the processor from which FlowFiles are allowed to be pulled
     */
    @Override
    public List<Connection> getPollableConnections() {
        if (pollFromSelfLoopsOnly()) {
            final List<Connection> selfLoops = new ArrayList<>();
            for (final Connection connection : connectable.getIncomingConnections()) {
                if (connection.getSource() == connection.getDestination()) {
                    selfLoops.add(connection);
                }
            }

            return selfLoops;
        } else {
            return connectable.getIncomingConnections();
        }
    }

    private boolean isTriggerWhenAnyDestinationAvailable() {
        if (connectable.getConnectableType() != ConnectableType.PROCESSOR) {
            return false;
        }

        final ProcessorNode procNode = (ProcessorNode) connectable;
        return procNode.isTriggerWhenAnyDestinationAvailable();
    }

    /**
     * @return true if we are allowed to take FlowFiles only from self-loops. This is the case when no Relationships are available except for self-looping Connections
     */
    private boolean pollFromSelfLoopsOnly() {
        if (isTriggerWhenAnyDestinationAvailable()) {
            // we can pull from any incoming connection, as long as at least one downstream connection
            // is available for each relationship.
            // I.e., we can poll only from self if no relationships are available
            return !Connectables.anyRelationshipAvailable(connectable);
        } else {
            for (final Connection connection : connectable.getConnections()) {
                // A downstream connection is full. We are only allowed to pull from self-loops.
                if (connection.getFlowFileQueue().isFull()) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void adjustCounter(final String name, final long delta) {
        final String localContext = connectable.getName() + " (" + connectable.getIdentifier() + ")";
        final String globalContext = "All " + connectable.getComponentType() + "'s";

        counterRepo.adjustCounter(localContext, name, delta);
        counterRepo.adjustCounter(globalContext, name, delta);
    }

    @Override
    public ContentRepository getContentRepository() {
        return contentRepo;
    }

    @Override
    public FlowFileRepository getFlowFileRepository() {
        return flowFileRepo;
    }

    @Override
    public FlowFileEventRepository getFlowFileEventRepository() {
        return flowFileEventRepo;
    }

    @Override
    public ProvenanceEventRepository getProvenanceRepository() {
        return provenanceRepo;
    }

    @Override
    public long getNextFlowFileSequence() {
        return flowFileRepo.getNextFlowFileSequence();
    }

    @Override
    public int getNextIncomingConnectionIndex() {
        final int numIncomingConnections = connectable.getIncomingConnections().size();
        return (int) (connectionIndex.getAndIncrement() % Math.max(1, numIncomingConnections));
    }


    /**
     * A Relationship is said to be Available if and only if all Connections for that Relationship are either self-loops or have non-full queues.
     *
     * @param requiredNumber minimum number of relationships that must have availability
     * @return Checks if at least <code>requiredNumber</code> of Relationationships are "available." If so, returns <code>true</code>, otherwise returns <code>false</code>
     */
    @Override
    public boolean isRelationshipAvailabilitySatisfied(final int requiredNumber) {
        int unavailable = 0;

        final Collection<Relationship> allRelationships = connectable.getRelationships();
        final int numRelationships = allRelationships.size();

        // the maximum number of Relationships that can be unavailable and still return true.
        final int maxUnavailable = numRelationships - requiredNumber;

        for (final Relationship relationship : allRelationships) {
            final Collection<Connection> connections = connectable.getConnections(relationship);
            if (connections != null && !connections.isEmpty()) {
                boolean available = true;
                for (final Connection connection : connections) {
                    // consider self-loops available
                    if (connection.getSource() == connection.getDestination()) {
                        continue;
                    }

                    if (connection.getFlowFileQueue().isFull()) {
                        available = false;
                        break;
                    }
                }

                if (!available) {
                    unavailable++;
                    if (unavailable > maxUnavailable) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    protected String getProvenanceComponentDescription() {
        return switch (connectable.getConnectableType()) {
            case PROCESSOR -> ((ProcessorNode) connectable).getComponentType();
            case INPUT_PORT -> "Input Port";
            case OUTPUT_PORT -> "Output Port";
            case REMOTE_INPUT_PORT -> ProvenanceEventRecord.REMOTE_INPUT_PORT_TYPE;
            case REMOTE_OUTPUT_PORT -> ProvenanceEventRecord.REMOTE_OUTPUT_PORT_TYPE;
            case FUNNEL -> "Funnel";
            default -> throw new AssertionError("Connectable type is " + connectable.getConnectableType());
        };
    }

    @Override
    public String getConnectableDescription() {
        if (connectable.getConnectableType() == ConnectableType.PROCESSOR) {
            return ((ProcessorNode) connectable).getProcessor().toString();
        }

        return connectable.toString();
    }

    @Override
    public ProvenanceEventBuilder createProvenanceEventBuilder() {
        return new StandardProvenanceEventRecord.Builder();
    }

    @Override
    public InternalProvenanceReporter createProvenanceReporter(final Predicate<FlowFile> flowfileKnownCheck, final ProvenanceEventEnricher eventEnricher) {
        final String componentType = getProvenanceComponentDescription();
        return new StandardProvenanceReporter(flowfileKnownCheck, getConnectable().getIdentifier(), componentType, getProvenanceRepository(), eventEnricher);
    }

    @Override
    public StateManager getStateManager() {
        return stateManager;
    }
}
