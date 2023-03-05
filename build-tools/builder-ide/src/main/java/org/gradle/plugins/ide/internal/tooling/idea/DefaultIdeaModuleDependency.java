package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.tooling.model.idea.IdeaDependencyScope;
import org.gradle.tooling.provider.model.internal.LegacyConsumerInterface;

@LegacyConsumerInterface("org.gradle.tooling.model.idea.IdeaModuleDependency")
public class DefaultIdeaModuleDependency extends DefaultIdeaDependency {
    private final String targetModuleName;
    private IdeaDependencyScope scope;
    private boolean exported;

    public DefaultIdeaModuleDependency(String targetModuleName) {
        this.targetModuleName = targetModuleName;
    }

    public IdeaDependencyScope getScope() {
        return scope;
    }

    public DefaultIdeaModuleDependency setScope(IdeaDependencyScope scope) {
        this.scope = scope;
        return this;
    }

    public String getTargetModuleName() {
        return targetModuleName;
    }

    public boolean getExported() {
        return exported;
    }

    public DefaultIdeaModuleDependency setExported(boolean exported) {
        this.exported = exported;
        return this;
    }

    @Override
    public String toString() {
        return "DefaultIdeaModuleDependency{"
            + "scope='" + scope + '\''
            + ", targetModuleName='" + targetModuleName + '\''
            + ", exported=" + exported
            + '}';
    }
}
