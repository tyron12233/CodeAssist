package org.gradle.plugins.ide.idea.model;

import com.google.common.base.Preconditions;
import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.gradle.api.Action;

import java.io.File;
import java.util.Map;

import static org.gradle.util.internal.ConfigureUtil.configure;

/**
 * DSL-friendly model of the IDEA project information.
 * First point of entry when it comes to customizing the IDEA generation.
 * <p>
 * See the examples in docs for {@link IdeaModule} or {@link IdeaProject}.
 */
public class IdeaModel {

    private IdeaModule module;
    private IdeaProject project;
    private IdeaWorkspace workspace = new IdeaWorkspace();
    private String targetVersion;

    /**
     * Configures IDEA module information. <p> For examples see docs for {@link IdeaModule}.
     */
    public IdeaModule getModule() {
        return module;
    }

    public void setModule(IdeaModule module) {
        this.module = module;
    }

    /**
     * Configures IDEA project information. <p> For examples see docs for {@link IdeaProject}.
     */
    public IdeaProject getProject() {
        return project;
    }

    public void setProject(IdeaProject project) {
        this.project = project;
    }

    /**
     * Configures IDEA workspace information.
     * <p>
     * For examples see docs for {@link IdeaWorkspace}.
     */
    public IdeaWorkspace getWorkspace() {
        return workspace;
    }

    public void setWorkspace(IdeaWorkspace workspace) {
        this.workspace = workspace;
    }

    /**
     * Configures the target IDEA version.
     */
    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    /**
     * Configures IDEA module information. <p> For examples see docs for {@link IdeaModule}.
     */
    public void module(@DelegatesTo(IdeaModule.class) Closure closure) {
        configure(closure, getModule());
    }

    /**
     * Configures IDEA module information. <p> For examples see docs for {@link IdeaModule}.
     * @since 3.5
     */
    public void module(Action<? super IdeaModule> action) {
        action.execute(getModule());
    }

    /**
     * Configures IDEA project information. <p> For examples see docs for {@link IdeaProject}.
     */
    public void project(@DelegatesTo(IdeaProject.class) Closure closure) {
        configure(closure, getProject());
    }

    /**
     * Configures IDEA project information. <p> For examples see docs for {@link IdeaProject}.
     * @since 3.5
     */
    public void project(Action<? super IdeaProject> action) {
        action.execute(getProject());
    }

    /**
     * Configures IDEA workspace information. <p> For examples see docs for {@link IdeaWorkspace}.
     */
    public void workspace(@DelegatesTo(IdeaWorkspace.class) Closure closure) {
        configure(closure, getWorkspace());
    }

    /**
     * Configures IDEA workspace information. <p> For examples see docs for {@link IdeaWorkspace}.
     * @since 3.5
     */
    public void workspace(Action<? super IdeaWorkspace> action) {
        action.execute(getWorkspace());
    }

    /**
     * Adds path variables to be used for replacing absolute paths in resulting files (*.iml, etc.). <p> For example see docs for {@link IdeaModule}.
     *
     * @param pathVariables A map with String-&gt;File pairs.
     */
    public void pathVariables(Map<String, File> pathVariables) {
        Preconditions.checkNotNull(pathVariables);
        module.getPathVariables().putAll(pathVariables);
    }
}
