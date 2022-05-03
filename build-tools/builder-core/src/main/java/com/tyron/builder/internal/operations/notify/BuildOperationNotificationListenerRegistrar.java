package com.tyron.builder.internal.operations.notify;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * Mechanism by which the scan plugin registers for notifications.
 * <p>
 * One instance of this exists per build tree.
 * Only one listener may register.
 * Subsequent attempts yield exceptions.
 *
 * @since 4.0
 */
//@UsedByScanPlugin("obtained from the root build's root project's service registry")
@ServiceScope(Scopes.BuildSession.class)
public interface BuildOperationNotificationListenerRegistrar {

    /**
     * The registered listener will receive notification for all build operations for the
     * current build execution, including those those operations that started before the
     * listener was registered.
     *
     * @since 4.4
     */
    void register(BuildOperationNotificationListener listener);

}
