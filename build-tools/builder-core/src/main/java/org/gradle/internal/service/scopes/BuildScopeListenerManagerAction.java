package org.gradle.internal.service.scopes;

import org.gradle.api.Action;
import org.gradle.internal.event.ListenerManager;

/**
 * Action executed when building a GradleLauncher, used by subprojects to
 * register build-scoped listeners.
 */
public interface BuildScopeListenerManagerAction extends Action<ListenerManager> {
}
