package com.tyron.builder.process;

import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.Input;

/**
 * Contains a subset of the <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/jpda/conninv.html">Java Debug Wire Protocol</a> properties.
 *
 * @since 5.6
 */
public interface JavaDebugOptions {

    /**
     * Whether to attach a debug agent to the forked process.
     */
    @Input Property<Boolean> getEnabled();

    /**
     * The debug port.
     */
    @Input Property<Integer> getPort();

    /**
     * Whether a socket-attach or a socket-listen type of debugger is expected.
     * <p>
     * In socked-attach mode (server = true) the process actively waits for the debugger to connect after the JVM
     * starts up. In socket-listen mode (server = false), the debugger should be already running before startup
     * waiting for the JVM connecting to it.
     *
     * @return Whether the process actively waits for the debugger to be attached.
     */
    @Input Property<Boolean> getServer();

    /**
     * Whether the forked process should be suspended until the connection to the debugger is established.
     */
    @Input Property<Boolean> getSuspend();
}
