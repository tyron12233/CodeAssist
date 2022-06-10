package org.gradle.api.resources;

import org.gradle.api.Buildable;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.HasInternalProtocol;

import javax.annotation.Nullable;
import java.io.File;
import java.io.Reader;

/**
 * A read-only body of text backed by a string, file, archive entry, or other source.
 * To create a text resource, use one of the factory methods in {@link TextResourceFactory}
 * (e.g. {@code project.resources.text.fromFile(myFile)}).
 *
 * @since 2.2
 */
@HasInternalProtocol
public interface TextResource extends Buildable {
    /**
     * Returns a string containing the resource's text
     *
     * @return a string containing the resource's text
     */
    String asString();

    /**
     * Returns an unbuffered {@link Reader} that allows the resource's text to be read. The caller is responsible for closing the reader.
     *
     * @return a reader that allows the resource's text to be read.
     */
    Reader asReader();

    /**
     * Returns a file containing the resource's text and using the given character encoding.
     * If this resource is backed by a file with a matching encoding, that file may be
     * returned. Otherwise, a temporary file will be created and returned.
     *
     * @param charset a character encoding (e.g. {@code "utf-8"})
     *
     * @return a file containing the resource's text and using the given character encoding
     */
    File asFile(String charset);

    /**
     * Same as {@code asFile(Charset.defaultCharset().name())}.
     */
    File asFile();

    /**
     * Returns the input properties registered when this resource is used as task input.
     * Not typically used directly.
     *
     * @return the input properties registered when this resource is used as task input
     */
    @Nullable
    @Optional
    @Input
    Object getInputProperties();

    /**
     * Returns the input files registered when this resource is used as task input.
     * Not typically used directly.
     *
     * @return the input files registered when this resource is used as task input
     */
    @Nullable
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    @InputFiles
    FileCollection getInputFiles();

    @Internal
    @Override
    TaskDependency getBuildDependencies();
}
