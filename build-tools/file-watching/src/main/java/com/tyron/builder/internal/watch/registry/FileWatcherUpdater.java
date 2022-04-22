package com.tyron.builder.internal.watch.registry;


import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.file.FileHierarchySet;

import javax.annotation.CheckReturnValue;
import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * <p>
 * Responsible for updating the file watchers based on changes to the contents of the virtual file system,
 * and changes to the file system hierarchies we are allowed to watch.</p>
 *
 * <p>
 * The following terms are worth distinguishing between:
 * </p>
 *
 * <dl>
 *     <dt>file system hierarchy</dt>
 *     <dd>A directory on the file system with all its descendants.</dd>
 *
 *     <dt>watchable hierarchies</dt>
 *     <dd>The list of file system hierarchies Gradle might want to watch if interesting content appears in the VFS for them.
 *     These are currently roots of the builds seen by the daemon during the current or previous invocations.
 *     Interesting content means that we keep track of something that actually exists under the hierarchy (so not exclusively missing files).
 *     Note that hierarchies can be nested in each other.</dd>
 *
 *     <dt>recently used watchable hierarchies</dt>
 *     <dd>The list of file system hierarchies Gradle has accessed recently. These include build roots from recent builds
 *     that we actually accessed.</dd>
 *
 *     <dt>watched files</dt>
 *     <dd>A {@link org.gradle.internal.file.FileHierarchySet} of the files that we are currently watching.
 *     This helps decide whether or not something is being watched in a quick way.</dd>
 *
 *     <dt>watched hierarchies</dt>
 *     <dd>The list of file system hierarchies we are currently watching.
 *     When hierarchies are nested inside each other, this includes only the outermost hierarchies.
 *     On OSs with hierarchical file system events (currently Windows and macOS) this is what we tell the
 *     operating system to watch.
 *     See {@link org.gradle.internal.watch.registry.impl.HierarchicalFileWatcherUpdater}.</dd>
 *
 *     <dt>watched directories</dt>
 *     <dd>On OSs with non-hierarchical file system events (currently Linux only) we don't watch whole
 *     hierarchies, but need to individually watch each directory and its immediate children.
 *     See {@link org.gradle.internal.watch.registry.impl.NonHierarchicalFileWatcherUpdater}.</dd>
 *
 *     <dt>probed hierarchies</dt>
 *     <dd>The list of file system hierarchies that we've activated a file system probe for.
 *     We list every hierarchy here, even if there are ones nested inside others.
 *     See {@link FileWatcherProbeRegistry}.</dd>
 * </dl>
 */
public interface FileWatcherUpdater {
    /**
     * Registers a watchable hierarchy.
     *
     * @see FileWatcherRegistry#registerWatchableHierarchy(File, SnapshotHierarchy)
     */
    void registerWatchableHierarchy(File watchableHierarchy, SnapshotHierarchy root);

    /**
     * Updates the watchers after changes to the root.
     *
     * @see FileWatcherRegistry#virtualFileSystemContentsChanged(Collection, Collection, SnapshotHierarchy)
     */
    void virtualFileSystemContentsChanged(Collection<FileSystemLocationSnapshot> removedSnapshots, Collection<FileSystemLocationSnapshot> addedSnapshots, SnapshotHierarchy root);

    /**
     * Trigger armed watch probe at given path.
     */
    void triggerWatchProbe(String path);

    /**
     * Remove watched hierarchies that have been moved.
     *
     * @see FileWatcherRegistry#updateVfsOnBuildStarted(SnapshotHierarchy, WatchMode, java.util.List)
     */
    @CheckReturnValue
    SnapshotHierarchy updateVfsOnBuildStarted(SnapshotHierarchy root, WatchMode watchMode, List<File> unsupportedFileSystems);

    /**
     * Remove everything from the root which can't be kept after the current build finished.
     *
     * @see FileWatcherRegistry#updateVfsOnBuildFinished(SnapshotHierarchy, WatchMode, int, List)
     */
    @CheckReturnValue
    SnapshotHierarchy updateVfsOnBuildFinished(SnapshotHierarchy root, WatchMode watchMode, int maximumNumberOfWatchedHierarchies, List<File> unsupportedFileSystems);

    /**
     * The files actually being watched right now.
     *
     * @see FileWatcherUpdater
     */
    FileHierarchySet getWatchedFiles();
}

