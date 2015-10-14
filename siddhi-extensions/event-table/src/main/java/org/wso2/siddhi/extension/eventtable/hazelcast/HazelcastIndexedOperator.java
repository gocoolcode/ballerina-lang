/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.extension.eventtable.hazelcast;

import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.exception.OperationNotSupportedException;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.util.collection.operator.Finder;
import org.wso2.siddhi.core.util.collection.operator.Operator;

import java.util.concurrent.ConcurrentMap;

import static org.wso2.siddhi.core.util.SiddhiConstants.ANY;

/**
 * Operator which is related to Indexed Hazelcast table operations
 */
public class HazelcastIndexedOperator implements Operator {
    private final long withinTime;
    private ExpressionExecutor expressionExecutor;
    private int matchingEventPosition;

    public HazelcastIndexedOperator(ExpressionExecutor expressionExecutor, int matchingEventPosition, long withinTime) {
        this.expressionExecutor = expressionExecutor;
        this.matchingEventPosition = matchingEventPosition;
        this.withinTime = withinTime;
    }

    @Override
    public Finder cloneFinder() {
        return new HazelcastIndexedOperator(expressionExecutor, matchingEventPosition, withinTime);
    }

    /**
     * Called to find a event from event table
     *
     * @param matchingEvent     the event to be matched with the events at the processor
     * @param candidateEvents   Map of candidate events
     * @param streamEventCloner StreamEventCloner to copy new StreamEvent from existing StreamEvent
     * @return StreamEvent  event found
     */
    @Override
    public StreamEvent find(ComplexEvent matchingEvent, Object candidateEvents, StreamEventCloner streamEventCloner) {
        Object matchingKey = expressionExecutor.execute(matchingEvent);
        if (candidateEvents instanceof ConcurrentMap) {
            StreamEvent streamEvent = ((ConcurrentMap<Object, StreamEvent>) candidateEvents).get(matchingKey);
            if (streamEvent == null) {
                return null;
            } else {
                if (withinTime != ANY) {
                    long timeDifference = Math.abs(matchingEvent.getTimestamp() - streamEvent.getTimestamp());
                    if (timeDifference > withinTime) {
                        return null;
                    }
                }
                return streamEventCloner.copyStreamEvent(streamEvent);
            }
        } else {
            throw new OperationNotSupportedException(HazelcastIndexedOperator.class.getCanonicalName()
                    + " does not support " + candidateEvents.getClass().getCanonicalName());
        }
    }

    /**
     * Called when deleting an event chunk from event table
     *
     * @param deletingEventChunk Event list for deletion
     * @param candidateEvents    Map of candidate events
     */
    @Override
    public void delete(ComplexEventChunk deletingEventChunk, Object candidateEvents) {
        deletingEventChunk.reset();
        while (deletingEventChunk.hasNext()) {
            ComplexEvent deletingEvent = deletingEventChunk.next();
            Object matchingKey = expressionExecutor.execute(deletingEvent);
            if (candidateEvents instanceof ConcurrentMap) {
                if (withinTime != ANY) {
                    StreamEvent streamEvent = ((ConcurrentMap<Object, StreamEvent>) candidateEvents).get(matchingKey);
                    if (streamEvent != null) {
                        long timeDifference = Math.abs(deletingEvent.getTimestamp() - streamEvent.getTimestamp());
                        if (timeDifference > withinTime) {
                            continue;
                        }
                    }
                }
                ((ConcurrentMap<Object, StreamEvent>) candidateEvents).remove(matchingKey);
            } else {
                throw new OperationNotSupportedException(HazelcastIndexedOperator.class.getCanonicalName()
                        + " does not support " + candidateEvents.getClass().getCanonicalName());
            }
        }
    }

    /**
     * Called when updating the event table entries
     *
     * @param updatingEventChunk Event list that needs to be updated
     * @param candidateEvents    Map of candidate events
     * @param mappingPosition    Mapping positions array
     */
    @Override
    public void update(ComplexEventChunk updatingEventChunk, Object candidateEvents, int[] mappingPosition) {
        updatingEventChunk.reset();
        while (updatingEventChunk.hasNext()) {
            ComplexEvent updatingEvent = updatingEventChunk.next();
            Object matchingKey = expressionExecutor.execute(updatingEvent);
            if (candidateEvents instanceof ConcurrentMap) {
                StreamEvent streamEvent = ((ConcurrentMap<Object, StreamEvent>) candidateEvents).get(matchingKey);
                if (streamEvent != null) {
                    if (withinTime != ANY) {
                        long timeDifference = Math.abs(updatingEvent.getTimestamp() - streamEvent.getTimestamp());
                        if (timeDifference > withinTime) {
                            continue;
                        }
                    }
                    for (int i = 0, size = mappingPosition.length; i < size; i++) {
                        streamEvent.setOutputData(updatingEvent.getOutputData()[i], mappingPosition[i]);
                        ((ConcurrentMap<Object, StreamEvent>) candidateEvents).replace(matchingKey, streamEvent);
                    }
                }
            } else {
                throw new OperationNotSupportedException(HazelcastIndexedOperator.class.getCanonicalName()
                        + " does not support " + candidateEvents.getClass().getCanonicalName());
            }
        }
    }

    /**
     * Called when having "in" condition, to check the existence of the event
     *
     * @param matchingEvent   Event that need to be check for existence
     * @param candidateEvents Map of candidate events
     * @return existenceOfEvent
     */
    @Override
    public boolean contains(ComplexEvent matchingEvent, Object candidateEvents) {
        StreamEvent matchingStreamEvent;
        if (matchingEvent instanceof StreamEvent) {
            matchingStreamEvent = ((StreamEvent) matchingEvent);
        } else {
            matchingStreamEvent = ((StateEvent) matchingEvent).getStreamEvent(matchingEventPosition);
        }
        Object matchingKey = expressionExecutor.execute(matchingStreamEvent);
        if (candidateEvents instanceof ConcurrentMap) {
            StreamEvent streamEvent = ((ConcurrentMap<Object, StreamEvent>) candidateEvents).get(matchingKey);
            if (streamEvent != null) {
                if (withinTime != ANY) {
                    long timeDifference = Math.abs(matchingStreamEvent.getTimestamp() - streamEvent.getTimestamp());
                    if (timeDifference > withinTime) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        } else {
            throw new OperationNotSupportedException(HazelcastIndexedOperator.class.getCanonicalName()
                    + " does not support " + candidateEvents.getClass().getCanonicalName());
        }
    }
}
