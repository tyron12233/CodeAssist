package com.tyron.builder.api.file;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;


/**
 * Operations on the file system.
 *
 * <p>An instance of this type can be injected into a task, plugin or other object by annotating a public constructor or property getter method with {@code javax.inject.Inject}.
 *
 * @since 6.0
 */
@ServiceScope(Scopes.Build.class)
public interface FileSystemOperations {

    /**
     * Copies the specified files.
     * The given action is used to configure a {@link CopySpec}, which is then used to copy the files.
     *
     * @param action Action to configure the CopySpec
     * @return {@link WorkResult} that can be used to check if the copy did any work.
     */
    WorkResult copy(Action<? super CopySpec> action);

    /**
     * Synchronizes the contents of a destination directory with some source directories and files.
     * The given action is used to configure a {@link CopySpec}, which is then used to synchronize the files.
     *
     * @param action action Action to configure the CopySpec.
     * @return {@link WorkResult} that can be used to check if the sync did any work.
     */
    WorkResult sync(Action<? super CopySpec> action);

    /**
     * Deletes the specified files.
     * The given action is used to configure a {@link DeleteSpec}, which is then used to delete the files.
     *
     * @param action Action to configure the DeleteSpec
     * @return {@link WorkResult} that can be used to check if delete did any work.
     */
    WorkResult delete(Action<? super DeleteSpec> action);
}
