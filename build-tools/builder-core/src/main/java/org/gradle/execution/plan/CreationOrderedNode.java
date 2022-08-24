package org.gradle.execution.plan;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for nodes that are ordered based on their order of creation.
 */
abstract public class CreationOrderedNode extends Node {

    private static final AtomicInteger ORDER_COUNTER = new AtomicInteger();

    private final int order = ORDER_COUNTER.incrementAndGet();

    public final int getOrder() {
        return order;
    }
}