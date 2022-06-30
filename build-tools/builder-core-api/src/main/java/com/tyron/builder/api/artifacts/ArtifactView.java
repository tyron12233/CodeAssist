package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.attributes.HasAttributes;
import com.tyron.builder.api.attributes.HasConfigurableAttributes;
import com.tyron.builder.api.file.FileCollection;

import java.util.function.Predicate;

/**
 * A view over the artifacts resolved for this set of dependencies.
 *
 * By default, the view returns all files and artifacts, but this can be restricted by component identifier or by attributes.
 *
 * @since 3.4
 */
public interface ArtifactView extends HasAttributes {

    /**
     * Returns the collection of artifacts matching the requested attributes that are sourced from Components matching the specified filter.
     */
    ArtifactCollection getArtifacts();

    /**
     * Returns the collection of artifact files matching the requested attributes that are sourced from Components matching the specified filter.
     */
    FileCollection getFiles();

    /**
     * Configuration for a defined artifact view.
     *
     * @since 4.0
     */
    interface ViewConfiguration extends HasConfigurableAttributes<ViewConfiguration> {
        /**
         * Specify a filter for the components that should be included in this view.
         * Only artifacts from components matching the supplied filter will be returned by {@link #getFiles()} or {@link #getArtifacts()}.
         *
         * This method cannot be called a multiple times for a view.
         */
        ViewConfiguration componentFilter(Predicate<? super ComponentIdentifier> componentFilter);

        /**
         * Determines whether the view should be resolved in a 'lenient' fashion.
         *
         * When set to <code>true</code>, this view will resolve as many artifacts and/or files as possible
         * collecting any failures.
         *
         * When set to <code>false</code>, any failures will be propagated as exceptions when the view is resolved.
         */
        boolean isLenient();

        /**
         * Specify if the view should be resolved in a 'lenient' fashion.
         *
         * When set to <code>true</code>, this view will resolve as many artifacts and/or files as possible
         * collecting any failures.
         *
         * When set to <code>false</code>, any failures will be propagated as exceptions when the view is resolved.
         */
        void setLenient(boolean lenient);

        /**
         * Specify if the view should be resolved in a 'lenient' fashion.
         *
         * When set to <code>true</code>, this view will resolve as many artifacts and/or files as possible
         * collecting any failures.
         *
         * When set to <code>false</code>, any failures will be propagated as exceptions when the view is resolved.
         */
        ViewConfiguration lenient(boolean lenient);

    }
}
