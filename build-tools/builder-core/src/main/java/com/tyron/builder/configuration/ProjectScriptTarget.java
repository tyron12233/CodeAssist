package com.tyron.builder.configuration;

import com.tyron.builder.api.artifacts.VersionCatalogsExtension;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectScript;
import com.tyron.builder.groovy.scripts.BasicScript;
import com.tyron.builder.groovy.scripts.internal.Permits;

import groovy.lang.Script;

public class ProjectScriptTarget implements ScriptTarget {
    private final ProjectInternal target;

    public ProjectScriptTarget(ProjectInternal target) {
        this.target = target;
    }

    @Override
    public PluginManagerInternal getPluginManager() {
        return target.getPluginManager();
    }

    @Override
    public String getId() {
        return "proj";
    }

    @Override
    public String getClasspathBlockName() {
        return "buildscript";
    }

    @Override
    public boolean getSupportsPluginsBlock() {
        return true;
    }

    @Override
    public boolean getSupportsPluginManagementBlock() {
        return false;
    }

    @Override
    public boolean getSupportsMethodInheritance() {
        return true;
    }

    @Override
    public Class<? extends BasicScript> getScriptClass() {
        return ProjectScript.class;
    }

    @Override
    public void attachScript(Script script) {
        target.setScript(script);
    }

    @Override
    public void addConfiguration(Runnable runnable, boolean deferrable) {
        if (deferrable) {
            target.addDeferredConfiguration(runnable);
        } else {
            runnable.run();
        }
    }

    @Override
    public Permits getPluginsBlockPermits() {
        VersionCatalogsExtension versionCatalogs = target.getExtensions().findByType(
                VersionCatalogsExtension.class);
        if (versionCatalogs != null) {
            return new Permits(versionCatalogs.getCatalogNames());
        }
        return Permits.none();
    }
}
