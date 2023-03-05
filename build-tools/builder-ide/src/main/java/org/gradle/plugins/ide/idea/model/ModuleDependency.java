package org.gradle.plugins.ide.idea.model;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import groovy.util.Node;

import java.util.Map;

/**
 * Represents an orderEntry of type module in the iml XML.
 */
public class ModuleDependency implements Dependency {

    private String name;
    private String scope;
    private boolean exported;

    public ModuleDependency(String name, String scope) {
        this.name = name;
        this.scope = scope;
        this.exported = false;
    }

    /**
     * The name of the module the module depends on.
     * Must not be null.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * The scope for this dependency. If null the scope attribute is not added.
     */
    @Override
    public String getScope() {
        return scope;
    }

    @Override
    public void setScope(String scope) {
        this.scope = scope;
    }

    public boolean isExported() {
        return exported;
    }

    public void setExported(boolean exported) {
        this.exported = exported;
    }

    @Override
    public void addToNode(Node parentNode) {
        Map<String, Object> attributes = Maps.newLinkedHashMap();
        attributes.put("type", "module");
        attributes.put("module-name", name);
        if (exported) {
            attributes.put("exported", "");
        }
        if (!Strings.isNullOrEmpty(scope) && !"COMPILE".equals(scope)) {
            attributes.put("scope", scope);
        }
        parentNode.appendNode("orderEntry", attributes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!getClass().equals(o.getClass())) {
            return false;
        }
        ModuleDependency that = (ModuleDependency) o;
        return Objects.equal(name, that.name) && scopeEquals(scope, that.scope);
    }

    private boolean scopeEquals(String lhs, String rhs) {
        if ("COMPILE".equals(lhs)) {
            return Strings.isNullOrEmpty(rhs) || "COMPILE".equals(rhs);
        } else if ("COMPILE".equals(rhs)) {
            return Strings.isNullOrEmpty(lhs) || "COMPILE".equals(lhs);
        } else {
            return Objects.equal(lhs, rhs);
        }
    }

    @Override
    public int hashCode() {
        int result;
        result = name.hashCode();
        result = 31 * result + getScopeHash();
        return result;
    }

    private int getScopeHash() {
        return (!Strings.isNullOrEmpty(scope) && !scope.equals("COMPILE")) ? scope.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "ModuleDependency{" + "name='" + name + "\'" + ", scope='" + scope + "\'" + "}";
    }
}
