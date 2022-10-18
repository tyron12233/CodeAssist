package org.gradle.internal.resource;

import com.google.common.hash.HashCode;
import org.gradle.api.resources.MissingResourceException;
import org.gradle.internal.DisplayName;
import org.gradle.internal.resource.Resource;
import org.gradle.api.resources.ResourceException;

import javax.annotation.Nullable;
import java.io.File;
import java.io.Reader;
import java.nio.charset.Charset;

/**
 * A {@code Resource} that has text content.
 */
public interface TextResource extends Resource {
    /**
     * Returns the location of this resource.
     */
    ResourceLocation getLocation();

    /**
     * A long display name for this resource. The display name must use absolute paths and assume no context.
     */
    DisplayName getLongDisplayName();

    /**
     * A short display name for this resource. The display name may use relative paths.
     */
    DisplayName getShortDisplayName();

    /**
     * Returns a file that contains the same content as this resource, encoded using the charset specified by {@link #getCharset()}.
     * Not all resources are available as a file.
     * Note that this method may return null when {@link ResourceLocation#getFile()} returns non-null, when the contents are different.
     *
     * @return A file containing this resource. Returns null if this resource is not available as a file.
     */
    @Nullable
    File getFile();

    /**
     * Returns the charset use to encode the file containing the resource's content, as returned by {@link #getFile()}.
     *
     * @return The charset. Returns null when this resource is not available as a file.
     */
    @Nullable
    Charset getCharset();

    /**
     * Returns true when the content of this resource is cached in-heap or uses a hard-coded value. Returns false when the content requires IO on each query.
     *
     * <p>When this method returns false, the caller should avoid querying the content more than once.</p>
     */
    boolean isContentCached();

    /**
     * Returns true if this resource exists, false if it does not exist. A resource exists when it has content associated with it.
     *
     * <p>Note that this method may be expensive when {@link #isContentCached()} returns false, depending on the implementation.
     *
     * @return true if this resource exists.
     * @throws ResourceException On failure to check whether resource exists.
     */
    boolean getExists() throws ResourceException;

    /**
     * Returns true when the content of this resource is empty. This method is may be more efficient than calling {@link #getText()} and checking the length.
     *
     * <p>Note that this method may be expensive when {@link #isContentCached()} returns false, depending on the implementation.
     *
     * @throws MissingResourceException When this resource does not exist.
     * @throws ResourceException On failure to read content.
     */
    boolean getHasEmptyContent() throws ResourceException;

    /**
     * Returns an *unbuffered* reader over the content of this resource.
     *
     * <p>Note that this method, or reading from the provided reader, may be expensive when {@link #isContentCached()} returns false, depending on the implementation.
     *
     * @throws MissingResourceException When this resource does not exist.
     * @throws ResourceException On failure to read content.
     */
    Reader getAsReader() throws ResourceException;

    /**
     * Returns the content of this resource, as a String.
     *
     * <p>Note that this method may be expensive when {@link #isContentCached()} returns false, depending on the implementation.
     *
     * @return the content. Never returns null.
     * @throws MissingResourceException When this resource does not exist.
     * @throws ResourceException On failure to read content.
     */
    String getText() throws ResourceException;

    /**
     * Returns a hashcode of this resource's contents.
     */
    HashCode getContentHash() throws ResourceException;
}