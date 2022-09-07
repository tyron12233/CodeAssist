package com.tyron.builder.api.transform;

import com.android.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * The output of a transform.
 * <p>
 * There is no direct access to a location to write. Instead, Transforms can ask to get the
 * location for given scopes, content-types and a format.
 * @deprecated
 */
@Deprecated
public interface TransformOutputProvider {

    /**
     * Delete all content. This is useful when running in non-incremental mode
     *
     * @throws IOException if deleting the output failed.
     */
    void deleteAll() throws IOException;

    /**
     * Returns the location of content for a given set of Scopes, Content Types, and Format.
     *
     * <p>If the format is {@link Format#DIRECTORY} then the result is the file location of the
     * directory.<br>
     * If the format is {@link Format#JAR} then the result is a file representing the jar to create.
     *
     * <p>Non of the directories or files are created by querying this method, and there is no
     * checks regarding the existence of content in this location.
     *
     * <p>In case of incremental processing of removed files, it is safe to query the method to get
     * the location of the files to removed.
     *
     * @param name a unique name for the content. For a given set of scopes/types/format it must be
     *     unique.
     * @param types the content types associated with this content.
     * @param scopes the scopes associated with this content.
     * @param format the format of the content.
     * @return the location of the content.
     * @deprecated
     */
    @NonNull
    File getContentLocation(
            @NonNull String name,
            @NonNull Set<QualifiedContent.ContentType> types,
            @NonNull Set<? super QualifiedContent.Scope> scopes,
            @NonNull Format format);
}
