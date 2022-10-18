package com.tyron.builder.merge;

import java.io.Closeable;

/**
 * Specifies the general contract for an object that needs to be open or closed. An
 * openable/closeable object has two states: open and closed. {@link #open()} moves from closed to
 * open and {@link #close()} from open to close. What operations can be performed in each state is
 * not specified by this interface, only the state machine.
 *
 * <p>Openable / closeable objects are always initialized as closed.
 */
public interface OpenableCloseable extends Closeable {

    /**
     * Opens the object.
     */
    void open();
}
