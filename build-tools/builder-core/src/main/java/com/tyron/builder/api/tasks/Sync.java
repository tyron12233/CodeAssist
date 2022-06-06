package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.file.copy.CopyAction;
import com.tyron.builder.api.internal.file.copy.CopySpecInternal;
import com.tyron.builder.api.internal.file.copy.DestinationRootCopySpec;
import com.tyron.builder.api.internal.file.copy.FileCopyAction;
import com.tyron.builder.api.internal.file.copy.SyncCopyActionDecorator;
import com.tyron.builder.api.tasks.util.PatternFilterable;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;

/**
 * Synchronizes the contents of a destination directory with some source directories and files.
 *
 * <p>
 * This task is like the {@link Copy} task, except the destination directory will only contain the files
 * copied. All files that exist in the destination directory will be deleted before copying files, unless
 * a {@link #preserve(Action)} is specified.
 *
 * <p>
 * Examples:
 * <pre class='autoTested'>
 *
 * // Sync can be used like a Copy task
 * // See the Copy documentation for more examples
 * task syncDependencies(type: Sync) {
 *     from 'my/shared/dependencyDir'
 *     into 'build/deps/compile'
 * }
 *
 * // You can preserve output that already exists in the
 * // destination directory. Files matching the preserve
 * // filter will not be deleted.
 * task sync(type: Sync) {
 *     from 'source'
 *     into 'dest'
 *     preserve {
 *         include 'extraDir/**'
 *         include 'dir1/**'
 *         exclude 'dir1/extra.txt'
 *     }
 * }
 * </pre>
 */
@DisableCachingByDefault(because = "Not worth caching")
public class Sync extends AbstractCopyTask {

    private final PatternFilterable preserveInDestination = new PatternSet();

    @Override
    protected CopyAction createCopyAction() {
        File destinationDir = getDestinationDir();
        if (destinationDir == null) {
            throw new InvalidUserDataException("No copy destination directory has been specified, use 'into' to specify a target directory.");
        }
        return new SyncCopyActionDecorator(
            destinationDir,
            new FileCopyAction(getFileLookup().getFileResolver(destinationDir)),
            preserveInDestination,
            getDeleter(),
            getDirectoryFileTreeFactory()
        );
    }

    @Override
    protected CopySpecInternal createRootSpec() {
        return getProject().getObjects().newInstance(DestinationRootCopySpec.class, super.createRootSpec());
    }

    @Override
    public DestinationRootCopySpec getRootSpec() {
        return (DestinationRootCopySpec) super.getRootSpec();
    }

    /**
     * Returns the directory to copy files into.
     *
     * @return The destination dir.
     */
    @OutputDirectory
    public File getDestinationDir() {
        return getRootSpec().getDestinationDir();
    }

    /**
     * Sets the directory to copy files into. This is the same as calling {@link #into(Object)} on this task.
     *
     * @param destinationDir The destination directory. Must not be null.
     */
    public void setDestinationDir(File destinationDir) {
        into(destinationDir);
    }

    /**
     * Returns the filter that defines which files to preserve in the destination directory.
     *
     * @return the filter defining the files to preserve
     *
     * @see #getDestinationDir()
     */
    @Internal
    public PatternFilterable getPreserve() {
        return preserveInDestination;
    }

    /**
     * Configures the filter that defines which files to preserve in the destination directory.
     *
     * @param action Action for configuring the preserve filter
     * @return this
     *
     * @see #getDestinationDir()
     */
    public Sync preserve(Action<? super PatternFilterable> action) {
        action.execute(preserveInDestination);
        return this;
    }

    @Inject
    protected Deleter getDeleter() {
        throw new UnsupportedOperationException("Decorator takes care of injection");
    }
}
