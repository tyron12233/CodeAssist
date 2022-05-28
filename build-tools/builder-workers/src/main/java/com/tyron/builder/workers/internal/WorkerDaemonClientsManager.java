package com.tyron.builder.workers.internal;

import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.internal.session.BuildSessionLifecycleListener;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.events.LogLevelChangeEvent;
import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.health.memory.OsMemoryInfo;
import com.tyron.builder.process.internal.worker.WorkerProcess;
import com.tyron.builder.util.internal.CollectionUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static java.util.Comparator.*;

public class WorkerDaemonClientsManager implements Stoppable {

    private static final Logger LOGGER = Logging.getLogger(WorkerDaemonClientsManager.class);

    private final Object lock = new Object();
    private final List<WorkerDaemonClient> allClients = new ArrayList<WorkerDaemonClient>();
    private final List<WorkerDaemonClient> idleClients = new ArrayList<WorkerDaemonClient>();
    private final Action<WorkerProcess> workerProcessCleanupAction = new WorkerProcessCleanupAction();

    private final WorkerDaemonStarter workerDaemonStarter;
    private final ListenerManager listenerManager;
    private final LoggingManagerInternal loggingManager;
    private final OsMemoryInfo memoryInfo;
    private final BuildSessionLifecycleListener stopSessionScopeWorkers;
    private final OutputEventListener logLevelChangeEventListener;
    private final WorkerDaemonExpiration workerDaemonExpiration;
    private final MemoryManager memoryManager;
    private volatile LogLevel currentLogLevel;

    public WorkerDaemonClientsManager(WorkerDaemonStarter workerDaemonStarter, ListenerManager listenerManager, LoggingManagerInternal loggingManager, MemoryManager memoryManager, OsMemoryInfo memoryInfo) {
        this.workerDaemonStarter = workerDaemonStarter;
        this.listenerManager = listenerManager;
        this.loggingManager = loggingManager;
        this.memoryInfo = memoryInfo;
        this.stopSessionScopeWorkers = new StopSessionScopedWorkers();
        listenerManager.addListener(stopSessionScopeWorkers);
        this.logLevelChangeEventListener = new LogLevelChangeEventListener();
        loggingManager.addOutputEventListener(logLevelChangeEventListener);
        this.currentLogLevel = loggingManager.getLevel();
        this.memoryManager = memoryManager;
        this.workerDaemonExpiration = new WorkerDaemonExpiration(this, getTotalPhysicalMemory());
        memoryManager.addMemoryHolder(workerDaemonExpiration);
    }

    // TODO - should supply and check for the same parameters as passed to reserveNewClient()
    public WorkerDaemonClient reserveIdleClient(DaemonForkOptions forkOptions) {
        return reserveIdleClient(forkOptions, idleClients);
    }

    WorkerDaemonClient reserveIdleClient(DaemonForkOptions forkOptions, List<WorkerDaemonClient> clients) {
        synchronized (lock) {
            Iterator<WorkerDaemonClient> it = clients.iterator();
            while (it.hasNext()) {
                WorkerDaemonClient candidate = it.next();
                if (candidate.isCompatibleWith(forkOptions)) {
                    it.remove();
                    if (candidate.getLogLevel() != currentLogLevel) {
                        // TODO: Send a message to workers to change their log level rather than stopping
                        LOGGER.info("Log level has changed, stopping idle worker daemon with out-of-date log level.");
                        candidate.stop();
                    } else {
                        return candidate;
                    }
                }
            }
            return null;
        }
    }

    public WorkerDaemonClient reserveNewClient(DaemonForkOptions forkOptions) {
        //allow the daemon to be started concurrently
        WorkerDaemonClient client = workerDaemonStarter.startDaemon(forkOptions, workerProcessCleanupAction);
        synchronized (lock) {
            allClients.add(client);
        }
        return client;
    }

    public void release(WorkerDaemonClient client) {
        synchronized (lock) {
            if (!client.isFailed()) {
                idleClients.add(client);
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopWorkers(allClients);
            allClients.clear();
            idleClients.clear();
            listenerManager.removeListener(stopSessionScopeWorkers);
            memoryManager.removeMemoryHolder(workerDaemonExpiration);
        }
        // Do not hold lock while removing listener, as the listener may still be receiving events on another thread and will need to acquire the lock to handle these events
        loggingManager.removeOutputEventListener(logLevelChangeEventListener);
    }

    private long getTotalPhysicalMemory() {
        try {
            return memoryInfo.getOsSnapshot().getTotalPhysicalMemory();
        } catch (UnsupportedOperationException e) {
            return -1;
        }
    }

    /**
     * Select idle daemon clients to stop.
     *
     * @param selectionFunction Gets all idle daemon clients, daemons of returned clients are stopped
     */
    public void selectIdleClientsToStop(Transformer<List<WorkerDaemonClient>, List<WorkerDaemonClient>> selectionFunction) {
        synchronized (lock) {
            List<WorkerDaemonClient> sortedClients = CollectionUtils.sort(idleClients, comparingInt(WorkerDaemonClient::getUses));
            List<WorkerDaemonClient> clientsToStop = selectionFunction.transform(new ArrayList<>(sortedClients));
            if (!clientsToStop.isEmpty()) {
                stopWorkers(clientsToStop);
            }
        }
    }

    private void stopWorkers(List<WorkerDaemonClient> clientsToStop) {
        if (clientsToStop.size() > 0) {
            int clientCount = clientsToStop.size();
            LOGGER.debug("Stopping {} worker daemon(s).", clientCount);
            List<Exception> failures = Lists.newArrayList();
            for (WorkerDaemonClient client : clientsToStop) {
                try {
                    client.stop();
                } catch (Exception e) {
                    failures.add(e);
                }
            }
            idleClients.removeAll(clientsToStop);
            allClients.removeAll(clientsToStop);
            if (!failures.isEmpty()) {
                if (failures.size() == 1) {
                    throw UncheckedException.throwAsUncheckedException(failures.get(0));
                } else {
                    throw new DefaultMultiCauseException("Not all worker daemon(s) could be stopped.", failures);
                }
            } else {
                LOGGER.info("Stopped {} worker daemon(s).", clientCount);
            }
        }
    }

    private class StopSessionScopedWorkers implements BuildSessionLifecycleListener {
        @Override
        public void beforeComplete() {
            synchronized (lock) {
                List<WorkerDaemonClient> sessionScopedClients = CollectionUtils.filter(allClients, client -> client.getKeepAliveMode() == KeepAliveMode.SESSION);
                stopWorkers(sessionScopedClients);
            }
        }
    }

    private class LogLevelChangeEventListener implements OutputEventListener {
        @Override
        public void onOutput(OutputEvent event) {
            if (event instanceof LogLevelChangeEvent) {
                LogLevelChangeEvent logLevelChangeEvent = (LogLevelChangeEvent) event;
                currentLogLevel = logLevelChangeEvent.getNewLogLevel();
            }
        }
    }

    private class WorkerProcessCleanupAction implements Action<WorkerProcess> {
        @Override
        public void execute(WorkerProcess workerProcess) {
            synchronized (lock) {
                Iterator<WorkerDaemonClient> iterator = allClients.iterator();
                while (iterator.hasNext()) {
                    WorkerDaemonClient client = iterator.next();
                    if (client.isProcess(workerProcess)) {
                        client.setFailed(true);
                        iterator.remove();
                    }
                }
            }
        }
    }
}
