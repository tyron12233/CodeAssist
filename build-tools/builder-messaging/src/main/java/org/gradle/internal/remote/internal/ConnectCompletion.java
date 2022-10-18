package org.gradle.internal.remote.internal;

import org.gradle.internal.serialize.StatefulSerializer;

/**
 * A builder that allows a {@link Connection} to be created once the underlying transport with the peer has been
 * established.
 */
public interface ConnectCompletion {
    /**
     * Creates the connection. Uses the specified serializer for all messages.
     *
     * @return The serializer to use.
     */
    <T> RemoteConnection<T> create(StatefulSerializer<T> serializer);
}
