package com.tyron.builder.cache.internal.locklistener;


/**
 * Packet type for communication about file locks.
 *
 * <p>For backward compatibility, enum constants must not be removed.
 */
public enum FileLockPacketType {
    UNKNOWN,
    UNLOCK_REQUEST,
    UNLOCK_REQUEST_CONFIRMATION,
    LOCK_RELEASE_CONFIRMATION
}