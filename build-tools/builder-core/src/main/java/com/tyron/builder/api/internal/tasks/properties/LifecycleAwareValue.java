package com.tyron.builder.api.internal.tasks.properties;

/**
 * An input property value may implement this interface to be notified when the task that owns it starts and completes execution.
 */
public interface LifecycleAwareValue {

    /**
     * Called immediately prior to this property being used as an input.
     * The property implementation may finalize the property value, prevent further changes to the value and enable caching of whatever state it requires to efficiently snapshot and query the input files during execution.
     */
    void prepareValue();

    /**
     * Called after the completion of the unit of work, regardless of the outcome. The property implementation can release any state that was cached during execution.
     */
    void cleanupValue();
}