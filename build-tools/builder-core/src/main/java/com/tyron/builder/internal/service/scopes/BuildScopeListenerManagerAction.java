package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.event.ListenerManager;

/**
 * Action executed when building a GradleLauncher, used by subprojects to
 * register build-scoped listeners.
 */
public interface BuildScopeListenerManagerAction extends Action<ListenerManager> {
}
