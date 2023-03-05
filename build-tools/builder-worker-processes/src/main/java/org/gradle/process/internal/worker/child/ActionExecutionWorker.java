package org.gradle.process.internal.worker.child;

import org.gradle.api.Action;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.process.internal.worker.WorkerProcessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The final stage of worker start-up. Takes care of executing the worker action.</p>
 *
 * <p>It is instantiated and called from {@link SystemApplicationClassLoaderWorker}.<p>
 */
public class ActionExecutionWorker implements Action<WorkerProcessContext> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionExecutionWorker.class);
    private final Action<? super WorkerProcessContext> action;

    public ActionExecutionWorker(Action<? super WorkerProcessContext> action) {
        this.action = action;
    }

    @Override
    public void execute(final WorkerProcessContext workerContext) {
        LOGGER.debug("Starting {}.", workerContext.getDisplayName());

        ObjectConnection clientConnection = workerContext.getServerConnection();
        clientConnection.addUnrecoverableErrorHandler(new Action<Throwable>() {
            @Override
            public void execute(Throwable throwable) {
                if (action instanceof Stoppable) {
                    ((Stoppable) action).stop();
                }
            }
        });

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(action.getClass().getClassLoader());
        try {
            action.execute(workerContext);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }

        LOGGER.debug("Completed {}.", workerContext.getDisplayName());
    }
}
