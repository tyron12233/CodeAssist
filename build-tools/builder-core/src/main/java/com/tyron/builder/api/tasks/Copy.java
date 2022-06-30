package com.tyron.builder.api.tasks;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.file.copy.CopyAction;
import com.tyron.builder.api.internal.file.copy.CopySpecInternal;
import com.tyron.builder.api.internal.file.copy.DestinationRootCopySpec;
import com.tyron.builder.api.internal.file.copy.FileCopyAction;
import com.tyron.builder.work.DisableCachingByDefault;

import java.io.File;

/**
 * Copies files into a destination directory. This task can also rename and filter files as it copies. The task
 * implements {@link com.tyron.builder.api.file.CopySpec CopySpec} for specifying what to copy.
 *
 * <p> Examples:
 * <pre class='autoTested'>
 * task copyDocs(type: Copy) {
 *     from 'src/main/doc'
 *     into 'build/target/doc'
 * }
 *
 * //for Ant filter
 * import org.apache.tools.ant.filters.ReplaceTokens
 *
 * //for including in the copy task
 * def dataContent = copySpec {
 *     from 'src/data'
 *     include '*.data'
 * }
 *
 * task initConfig(type: Copy) {
 *     from('src/main/config') {
 *         include '**&#47;*.properties'
 *         include '**&#47;*.xml'
 *         filter(ReplaceTokens, tokens: [version: '2.3.1'])
 *     }
 *     from('src/main/config') {
 *         exclude '**&#47;*.properties', '**&#47;*.xml'
 *     }
 *     from('src/main/languages') {
 *         rename 'EN_US_(.*)', '$1'
 *     }
 *     into 'build/target/config'
 *     exclude '**&#47;*.bak'
 *
 *     includeEmptyDirs = false
 *
 *     with dataContent
 * }
 * </pre>
 */
@DisableCachingByDefault(because = "Not worth caching")
public class Copy extends AbstractCopyTask {

    @Override
    protected CopyAction createCopyAction() {
        File destinationDir = getDestinationDir();
        if (destinationDir == null) {
            throw new InvalidUserDataException("No copy destination directory has been specified, use 'into' to specify a target directory.");
        }
        return new FileCopyAction(getFileLookup().getFileResolver(destinationDir));
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
}
