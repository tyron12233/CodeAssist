package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.AsyncStoppable;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.remote.ConnectionAcceptor;
import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.internal.ExecException;
import com.tyron.builder.process.internal.ExecHandle;
import com.tyron.builder.process.internal.ExecHandleListener;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.String.format;

public class DefaultWorkerProcess implements WorkerProcess {
    private final static Logger LOGGER = Logging.getLogger(DefaultWorkerProcess.class);
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private ObjectConnection connection;
    private ConnectionAcceptor acceptor;
    private ExecHandle execHandle;
    private boolean running;
    private boolean aborted;
    private Throwable processFailure;
    private final long connectTimeout;
    private final JvmMemoryStatus jvmMemoryStatus;

    public DefaultWorkerProcess(int connectTimeoutValue, TimeUnit connectTimeoutUnits, @Nullable JvmMemoryStatus jvmMemoryStatus) {
        connectTimeout = connectTimeoutUnits.toMillis(connectTimeoutValue);
        this.jvmMemoryStatus = jvmMemoryStatus;
    }

    @Override
    public JvmMemoryStatus getJvmMemoryStatus() {
        if (jvmMemoryStatus != null) {
            return jvmMemoryStatus;
        } else {
            throw new UnsupportedOperationException("This worker process does not support reporting JVM memory status.");
        }
    }

    @Override
    public void stopNow() {
        lock.lock();
        try {
            aborted = true;
            if (connection != null) {
                connection.abort();
            }
        } finally {
            lock.unlock();

            // cleanup() will abort the process as desired
            cleanup();
        }
    }

    public void setExecHandle(ExecHandle execHandle) {
        this.execHandle = execHandle;
        execHandle.addListener(new ExecHandleListener() {
            @Override
            public void executionStarted(ExecHandle execHandle) {
            }

            @Override
            public void executionFinished(ExecHandle execHandle, ExecResult execResult) {
                onProcessStop(execResult);
            }
        });
    }

    public void startAccepting(ConnectionAcceptor acceptor) {
        lock.lock();
        try {
            this.acceptor = acceptor;
        } finally {
            lock.unlock();
        }
    }

    public void onConnect(ObjectConnection connection) {
        onConnect(connection, null);
    }

    public void onConnect(ObjectConnection connection, Runnable connectionHandler) {
        AsyncStoppable stoppable;
        lock.lock();
        try {
            LOGGER.debug("Received connection {} from {}", connection, execHandle);

            if (connectionHandler != null && running) {
                connectionHandler.run();
            }

            this.connection = connection;
            if (aborted) {
                connection.abort();
            }
            condition.signalAll();
            stoppable = acceptor;
        } finally {
            lock.unlock();
        }

        if (stoppable != null) {
            stoppable.requestStop();
        }
    }

    private void onProcessStop(ExecResult execResult) {
        lock.lock();
        try {
            try {
                execResult.rethrowFailure().assertNormalExitValue();
            } catch (Throwable e) {
                processFailure = e;
            }
            running = false;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "DefaultWorkerProcess{"
                + "running=" + running
                + ", execHandle=" + execHandle
                + '}';
    }

    @Override
    public ObjectConnection getConnection() {
        return connection;
    }

    @Override
    public WorkerProcess start() {
        try {
            doStart();
        } catch (Throwable t) {
            cleanup();
            throw UncheckedException.throwAsUncheckedException(t);
        }
        return this;
    }

    private void doStart() {
        lock.lock();
        try {
            running = true;
        } finally {
            lock.unlock();
        }

        execHandle.start();

        Date connectExpiry = new Date(System.currentTimeMillis() + connectTimeout);
        lock.lock();
        try {
            while (connection == null && running) {
                try {
                    if (!condition.awaitUntil(connectExpiry)) {
                        throw new ExecException(format("Unable to connect to the child process '%s'.\n"
                                + "It is likely that the child process have crashed - please find the stack trace in the build log.\n"
                                + "This exception might occur when the build machine is extremely loaded.\n"
                                + "The connection attempt hit a timeout after %.1f seconds (last known process state: %s, running: %s).", execHandle, ((double) connectTimeout) / 1000, execHandle.getState(), running));
                    }
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
            if (connection == null) {
                if (processFailure != null) {
                    throw UncheckedException.throwAsUncheckedException(processFailure);
                } else {
                    throw new ExecException(format("Never received a connection from %s.", execHandle));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ExecResult waitForStop() {
        try {
            return execHandle.waitForFinish().assertNormalExitValue();
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        CompositeStoppable stoppable;
        lock.lock();
        try {
            stoppable = CompositeStoppable.stoppable(connection, new Stoppable() {
                @Override
                public void stop() {
                    execHandle.abort();
                }
            }, acceptor);
        } finally {
            this.connection = null;
            this.acceptor = null;
            lock.unlock();
        }
        stoppable.stop();
    }
}
