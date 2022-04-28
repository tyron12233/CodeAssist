package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scope;

import java.util.EnumSet;

@EventScope(Scope.Global.class)
public interface WorkInputListener {
    /**
     * Called when the execution of the given work item is imminent, or would have been if the primary inputs would not have been empty.
     * <p>
     *
     * @param work the identity of the unit of work to be executed
     * @param relevantTypes the file system inputs relevant to the task execution
     */
    void onExecute(UnitOfWork work, EnumSet<InputFingerprinter.InputPropertyType> relevantTypes);
}