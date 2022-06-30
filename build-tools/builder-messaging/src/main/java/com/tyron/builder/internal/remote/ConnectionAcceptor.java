package com.tyron.builder.internal.remote;

import com.tyron.builder.internal.concurrent.AsyncStoppable;

public interface ConnectionAcceptor extends AsyncStoppable {
    Address getAddress();

    /**
     * Stops accepting incoming connections.
     */
    @Override
    void requestStop();

    /**
     * Stops accepting incoming connections and blocks until the accept action has completed executing for any queued connections.
     */
    @Override
    void stop();
}
