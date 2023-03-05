package org.gradle.internal.remote.internal.hub;

import org.gradle.api.Action;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.remote.ConnectionAcceptor;
import org.gradle.internal.remote.MessagingServer;
import org.gradle.internal.remote.internal.IncomingConnector;
import org.gradle.internal.remote.ObjectConnection;
import org.gradle.internal.remote.internal.ConnectCompletion;

public class MessageHubBackedServer implements MessagingServer {
    private final IncomingConnector connector;
    private final ExecutorFactory executorFactory;

    public MessageHubBackedServer(IncomingConnector connector, ExecutorFactory executorFactory) {
        this.connector = connector;
        this.executorFactory = executorFactory;
    }

    @Override
    public ConnectionAcceptor accept(Action<ObjectConnection> action) {
        return connector.accept(new ConnectEventAction(action), false);
    }

    private class ConnectEventAction implements Action<ConnectCompletion> {
        private final Action<ObjectConnection> action;

        public ConnectEventAction(Action<ObjectConnection> action) {
            this.action = action;
        }

        @Override
        public void execute(ConnectCompletion completion) {
            action.execute(new MessageHubBackedObjectConnection(executorFactory, completion));
        }
    }

}
