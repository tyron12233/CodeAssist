package org.gradle.plugins.ide.api;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.internal.MutableActionSet;
import org.gradle.util.internal.ConfigureUtil;

/**
 * Models the generation/parsing/merging capabilities.
 * <p>
 * For examples see docs for {@link org.gradle.plugins.ide.eclipse.model.EclipseProject}
 * or {@link org.gradle.plugins.ide.idea.model.IdeaProject} and others.
 */
public class FileContentMerger {

    private MutableActionSet whenMerged = new MutableActionSet();
    private MutableActionSet beforeMerged = new MutableActionSet();

    public MutableActionSet getWhenMerged() {
        return whenMerged;
    }

    public void setWhenMerged(MutableActionSet whenMerged) {
        this.whenMerged = whenMerged;
    }

    public MutableActionSet getBeforeMerged() {
        return beforeMerged;
    }

    public void setBeforeMerged(MutableActionSet beforeMerged) {
        this.beforeMerged = beforeMerged;
    }


    /**
     * Adds an action to be called after content is loaded from existing file but before gradle build information is merged.
     * <p>
     * This is advanced api that gives access to internal implementation.
     * It might be useful if you want to alter the way gradle build information is merged into existing file content.
     * <p>
     * For examples see docs for {@link org.gradle.plugins.ide.eclipse.model.EclipseProject}
     * or {@link org.gradle.plugins.ide.idea.model.IdeaProject} and others.
     *
     * @param action The action to execute.
     */
    @SuppressWarnings("unchecked")
    public void beforeMerged(Action<?> action) {
        beforeMerged.add(action);
    }

    /**
     * Adds an action to be called after content is loaded from existing file and after gradle build information is merged.
     * <p>
     * This is advanced api that gives access to internal implementation of idea plugin.
     * Use it only to tackle some tricky edge cases.
     * <p>
     * For examples see docs for {@link org.gradle.plugins.ide.eclipse.model.EclipseProject}
     * or {@link org.gradle.plugins.ide.idea.model.IdeaProject} and others.
     *
     * @param action The action to execute.
     */
    @SuppressWarnings("unchecked")
    public void whenMerged(Action<?> action) {
        whenMerged.add(action);
    }

    /**
     * Adds a closure to be called after content is loaded from existing file but before gradle build information is merged.
     * <p>
     * This is advanced api that gives access to internal implementation.
     * It might be useful if you want to alter the way gradle build information is merged into existing file content.
     * <p>
     * For examples see docs for {@link org.gradle.plugins.ide.eclipse.model.EclipseProject}
     * or {@link org.gradle.plugins.ide.idea.model.IdeaProject} and others.
     *
     * @param closure The closure to execute.
     */
    @SuppressWarnings("unchecked")
    public void beforeMerged(Closure closure) {
        beforeMerged.add(ConfigureUtil.configureUsing(closure));
    }

    /**
     * Adds a closure to be called after content is loaded from existing file and after gradle build information is merged.
     * <p>
     * This is advanced api that gives access to internal implementation of idea plugin.
     * Use it only to tackle some tricky edge cases.
     * <p>
     * For examples see docs for {@link org.gradle.plugins.ide.eclipse.model.EclipseProject}
     * or {@link org.gradle.plugins.ide.idea.model.IdeaProject} and others.
     *
     * @param closure The closure to execute.
     */
    @SuppressWarnings("unchecked")
    public void whenMerged(Closure closure) {
        whenMerged.add(ConfigureUtil.configureUsing(closure));
    }
}