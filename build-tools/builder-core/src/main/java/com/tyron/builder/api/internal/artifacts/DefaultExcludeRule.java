package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ExcludeRule;

public class DefaultExcludeRule implements ExcludeRule {
    private String group;
    private String module;

    public DefaultExcludeRule(){
    }

    public DefaultExcludeRule(String group, String module) {
        this.group = group;
        this.module = module;
    }

    @Override
    public String getGroup() {
        return group;
    }

    public void setGroup(String groupValue) {
        this.group = groupValue;
    }

    @Override
    public String getModule() {
        return module;
    }

    public void setModule(String moduleValue) {
        this.module = moduleValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultExcludeRule that = (DefaultExcludeRule) o;

        if (group != null ? !group.equals(that.group) : that.group != null) {
            return false;
        }
        if (module != null ? !module.equals(that.module) : that.module != null) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = group != null ? group.hashCode() : 0;
        result = 31 * result + (module != null ? module.hashCode() : 0);
        return result;
    }
}
