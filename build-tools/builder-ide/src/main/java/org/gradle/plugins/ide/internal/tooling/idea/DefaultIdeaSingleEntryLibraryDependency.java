package org.gradle.plugins.ide.internal.tooling.idea;

import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaDependencyScope;
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency;
import org.gradle.tooling.provider.model.internal.LegacyConsumerInterface;

import java.io.File;

@LegacyConsumerInterface("org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency")
public class DefaultIdeaSingleEntryLibraryDependency extends DefaultIdeaDependency implements IdeaSingleEntryLibraryDependency {
    private File file;
    private File source;
    private File javadoc;
    private Boolean exported;
    private IdeaDependencyScope scope;
    private GradleModuleVersion moduleVersion;

    public File getFile() {
        return file;
    }

    public DefaultIdeaSingleEntryLibraryDependency setFile(File file) {
        this.file = file;
        return this;
    }

    public File getSource() {
        return source;
    }

    public DefaultIdeaSingleEntryLibraryDependency setSource(File source) {
        this.source = source;
        return this;
    }

    public File getJavadoc() {
        return javadoc;
    }

    @Override
    public boolean isExported() {
        return getExported();
    }

    public GradleModuleVersion getGradleModuleVersion() {
        return moduleVersion;
    }

    public DefaultIdeaSingleEntryLibraryDependency setJavadoc(File javadoc) {
        this.javadoc = javadoc;
        return this;
    }

    public boolean getExported() {
        return exported;
    }

    public DefaultIdeaSingleEntryLibraryDependency setExported(Boolean exported) {
        this.exported = exported;
        return this;
    }

    public IdeaDependencyScope getScope() {
        return scope;
    }

    public DefaultIdeaSingleEntryLibraryDependency setScope(IdeaDependencyScope scope) {
        this.scope = scope;
        return this;
    }

    public DefaultIdeaSingleEntryLibraryDependency setGradleModuleVersion(GradleModuleVersion moduleVersion) {
        this.moduleVersion = moduleVersion;
        return this;
    }

    @Override
    public String toString() {
        return "IdeaLibraryDependency{"
                + "file=" + file
                + ", source=" + source
                + ", javadoc=" + javadoc
                + ", exported=" + exported
                + ", scope='" + scope + '\''
                + ", id='" + moduleVersion + '\''
                + '}';
    }
}
