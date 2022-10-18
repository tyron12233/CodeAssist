package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.tooling.model.idea.IdeaLanguageLevel;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;

public class DefaultIdeaProject implements Serializable {
    private String name;
    private String description;
    private Collection<DefaultIdeaModule> children = new LinkedList<DefaultIdeaModule>();
    private IdeaLanguageLevel languageLevel;
    private String jdkName;
    private DefaultIdeaJavaLanguageSettings javaLanguageSettings;

    public IdeaLanguageLevel getLanguageLevel() {
        return languageLevel;
    }

    public DefaultIdeaProject setLanguageLevel(IdeaLanguageLevel languageLevel) {
        this.languageLevel = languageLevel;
        return this;
    }

    public String getJdkName() {
        return jdkName;
    }

    public DefaultIdeaProject setJdkName(String jdkName) {
        this.jdkName = jdkName;
        return this;
    }

    public String getName() {
        return name;
    }

    public DefaultIdeaProject setName(String name) {
        this.name = name;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public DefaultIdeaProject setDescription(String description) {
        this.description = description;
        return this;
    }

    public Object getParent() {
        return null;
    }

    public DefaultIdeaProject setChildren(Collection<? extends DefaultIdeaModule> children) {
        this.children.clear();
        this.children.addAll(children);
        return this;
    }

    public Collection<DefaultIdeaModule> getChildren() {
        return children;
    }

    public Collection<DefaultIdeaModule> getModules() {
        return children;
    }

    public DefaultIdeaJavaLanguageSettings getJavaLanguageSettings() {
        return javaLanguageSettings;
    }

    public DefaultIdeaProject setJavaLanguageSettings(DefaultIdeaJavaLanguageSettings javaLanguageSettings) {
        this.javaLanguageSettings = javaLanguageSettings;
        return this;
    }

    @Override
    public String toString() {
        return "DefaultIdeaProject{"
                + " name='" + name + '\''
                + ", description='" + description + '\''
                + ", children count=" + children.size()
                + ", languageLevel='" + languageLevel + '\''
                + ", jdkName='" + jdkName + '\''
                + '}';
    }
}