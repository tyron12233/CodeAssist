package com.tyron.builder.internal.remote.internal.hub;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.remote.ConnectionAcceptor;
import com.tyron.builder.internal.remote.MessagingServer;
import com.tyron.builder.internal.remote.internal.IncomingConnector;
import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.internal.remote.internal.ConnectCompletion;

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
