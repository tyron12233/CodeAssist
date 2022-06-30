package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;

import java.util.EnumSet;

public interface WorkInputListeners {
    /**
     * Registers the listener with the build, the listener can be unregistered with {@link #removeListener(WorkInputListener)}.
     */
    void addListener(WorkInputListener listener);

    void removeListener(WorkInputListener listener);

    void broadcastFileSystemInputsOf(UnitOfWork work, EnumSet<InputFingerprinter.InputPropertyType> relevantTypes);
}