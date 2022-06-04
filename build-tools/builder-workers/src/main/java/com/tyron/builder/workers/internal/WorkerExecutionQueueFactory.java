package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.work.ConditionalExecutionQueue;
import com.tyron.builder.internal.work.ConditionalExecutionQueueFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class WorkerExecutionQueueFactory implements Factory<ConditionalExecutionQueue<DefaultWorkResult>>, Stoppable {
    public static final String QUEUE_DISPLAY_NAME = "WorkerExecutor Queue";
    private final ConditionalExecutionQueueFactory conditionalExecutionQueueFactory;
    private ConditionalExecutionQueue<DefaultWorkResult> queue;

    public WorkerExecutionQueueFactory(ConditionalExecutionQueueFactory conditionalExecutionQueueFactory) {
        this.conditionalExecutionQueueFactory = conditionalExecutionQueueFactory;
    }

    @Nullable
    @Override
    public synchronized ConditionalExecutionQueue<DefaultWorkResult> create() {
        if (queue == null) {
            queue = conditionalExecutionQueueFactory.create(QUEUE_DISPLAY_NAME, DefaultWorkResult.class);
        }
        return queue;
    }

    @Override
    public synchronized void stop() {
        if (queue != null) {
            queue.stop();
        }
    }
}
