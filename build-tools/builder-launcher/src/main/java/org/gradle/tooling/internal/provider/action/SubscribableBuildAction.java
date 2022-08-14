package org.gradle.tooling.internal.provider.action;

import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.invocation.BuildAction;

public abstract class SubscribableBuildAction implements BuildAction {
    private final BuildEventSubscriptions clientSubscriptions;

    public SubscribableBuildAction(BuildEventSubscriptions clientSubscriptions) {
        this.clientSubscriptions = clientSubscriptions;
    }

    public BuildEventSubscriptions getClientSubscriptions() {
        return clientSubscriptions;
    }

}
