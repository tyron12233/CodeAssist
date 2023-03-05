package com.tyron.builder.api.transform;

import com.android.annotations.NonNull;
import java.io.File;
import java.util.Set;

/**
 * Represent content qualified with one or more {@link ContentType} and one or more {@link Scope}.
 * @deprecated
 */
@Deprecated
public interface QualifiedContent {

    /**
     * A content type that is requested through the transform API.
     */
    interface ContentType {

        /**
         * Content type name, readable by humans.
         * @return the string content type name
         */
        String name();

        /**
         * A unique value for a content type.
         */
        int getValue();
    }

    /**
     * The type of of the content.
     */
    enum DefaultContentType implements ContentType {
        /**
         * The content is compiled Java code. This can be in a Jar file or in a folder. If
         * in a folder, it is expected to in sub-folders matching package names.
         */
        CLASSES(0x01),

        /** The content is standard Java resources. */
        RESOURCES(0x02);

        private final int value;

        DefaultContentType(int value) {
            this.value = value;
        }

        @Override
        public int getValue() {
            return value;
        }
    }

    /**
     * Definition of a scope.
     */
    interface ScopeType {

        /**
         * Scope name, readable by humans.
         * @return a scope name.
         */
        String name();

        /**
         * A scope binary flag that will be used to encode directory names. Must be unique.
         * @return a scope binary flag.
         */
        int getValue();
    }

    /**
     * The scope of the content.
     *
     * <p>
     * This indicates what the content represents, so that Transforms can apply to only part(s)
     * of the classes or resources that the build manipulates.
     */
    enum Scope implements ScopeType {
        /** Only the project (module) content */
        PROJECT(0x01),
        /** Only the sub-projects (other modules) */
        SUB_PROJECTS(0x04),
        /** Only the external libraries */
        EXTERNAL_LIBRARIES(0x10),
        /** Code that is being tested by the current variant, including dependencies */
        TESTED_CODE(0x20),
        /** Local or remote dependencies that are provided-only */
        PROVIDED_ONLY(0x40),

        /**
         * Only the project's local dependencies (local jars)
         *
         * @deprecated local dependencies are now processed as {@link #EXTERNAL_LIBRARIES}
         */
        @Deprecated
        PROJECT_LOCAL_DEPS(0x02),
        /**
         * Only the sub-projects's local dependencies (local jars).
         *
         * @deprecated local dependencies are now processed as {@link #EXTERNAL_LIBRARIES}
         */
        @Deprecated
        SUB_PROJECTS_LOCAL_DEPS(0x08);

        private final int value;

        Scope(int value) {
            this.value = value;
        }

        @Override
        public int getValue() {
            return value;
        }
    }

    /**
     * Returns the name of the content. Can be used to differentiate different content using
     * the same scope.
     *
     * This is not reliably usable at every stage of the transformations, but can be used for
     * logging for instance.
     *
     * @return the name
     */
    @NonNull
    String getName();

    /**
     * Returns he location of the content.
     *
     * @return the content location.
     */
    @NonNull
    File getFile();

    /**
     * Returns the type of content that the stream represents.
     * <p>
     * Even though this may return only {@link DefaultContentType#RESOURCES} or
     * {@link DefaultContentType#CLASSES}, the actual content (the folder or the jar) may
     * contain files representing other content types. This is because the transform mechanism
     * avoids duplicating files around to remove unwanted types for performance.
     * <p>
     * For each input, transforms should always take care to read and process only the files
     * associated with the types returned by this method.
     *
     * @return a set of one or more types, never null nor empty.
     */
    @NonNull
    Set<ContentType> getContentTypes();

    /**
     * Returns the scope of the content.
     *
     * @return a set of one or more scopes, never null nor empty.
     */
    @NonNull
    Set<? super Scope> getScopes();
}
