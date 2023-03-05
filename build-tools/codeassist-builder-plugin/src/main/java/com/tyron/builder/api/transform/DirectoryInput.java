package com.tyron.builder.api.transform;

import com.android.annotations.NonNull;
import java.io.File;
import java.util.Collection;
import java.util.Map;

/**
 * A {@link QualifiedContent} of type directory.
 * <p>
 * This means the {@link #getFile()} is the root directory containing the content.
 * <p>
 * This also contains incremental data if the transform is in incremental mode through
 * {@link #getChangedFiles()}.
 * <p>
 * For a transform to run in incremental mode:
 * <ul>
 *     <li>{@link Transform#isIncremental()} must return <code>true</code></li>
 *     <li>The parameter <var>isIncremental</var> of
 *     {@link Transform#transform(Context, Collection, Collection, TransformOutputProvider, boolean)}
 *     must be <code>true</code>.</li>
 * </ul>
 *
 * If the transform is not in incremental mode, {@link #getChangedFiles()} will not contain any
 * information (it will <strong>not</strong> contain the list of all the files with state
 * {@link Status#NOTCHANGED}.)
 *
 * <p>
 * When a root level directory is removed, and incremental mode is on, the instance will receive
 * a {@link DirectoryInput} instance for the removed folder, but {@link QualifiedContent#getFile()}
 * will return a directory that does not exist. In this case, the transform should prcess this
 * as a removed input.
 * @deprecated
 */
@Deprecated
public interface DirectoryInput extends QualifiedContent {

    /**
     * Returns the changed files. This is only valid if the transform is in incremental mode.
     */
    @NonNull
    Map<File, Status> getChangedFiles();
}
