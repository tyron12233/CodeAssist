package org.gradle.internal.execution;

import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.fingerprint.InputFingerprinter;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scope;

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