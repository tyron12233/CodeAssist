package org.gradle.plugins.ide.internal.tooling.idea;

import com.google.common.base.Objects;
import org.gradle.tooling.model.idea.IdeaDependencyScope;

import java.io.Serializable;

public class DefaultIdeaDependencyScope implements IdeaDependencyScope, Serializable {

    String scope;

    public DefaultIdeaDependencyScope(String scope) {
        this.scope = scope;
    }

    @Override
    public String getScope() {
        return scope;
    }

    @Override
    public String toString() {
        return "IdeaDependencyScope{"
                + "scope='" + scope + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultIdeaDependencyScope)) {
            return false;
        }

        DefaultIdeaDependencyScope that = (DefaultIdeaDependencyScope) o;
        return Objects.equal(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return scope != null ? scope.hashCode() : 0;
    }
}