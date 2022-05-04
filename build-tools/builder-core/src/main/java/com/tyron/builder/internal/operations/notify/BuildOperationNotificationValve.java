package com.tyron.builder.internal.operations.notify;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * Controls an instance of build operation notifications.
 *
 * This is required as the build operation notification machinery is effectively session scoped,
 * but we need to allow, external (i.e. non ListenerManager), listeners per build.
 *
 * Furthermore, the actual lifecycle is not something that we currently model with the service registries.
 * The notification listener is effectively of cross build tree scope, which doesn't exist.
 * This is because GradleBuild uses a discrete tree (which is intended to change later).
 */
@ServiceScope(Scopes.BuildSession.class)
public interface BuildOperationNotificationValve {

    void start();

    void stop();

}
