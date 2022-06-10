package org.gradle.internal.execution.history;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.execution.history.changes.AbsolutePathChangeDetector;
import org.gradle.internal.execution.history.changes.Change;
import org.gradle.internal.execution.history.changes.ChangeContainer;
import org.gradle.internal.execution.history.changes.ChangeVisitor;
import org.gradle.internal.execution.history.changes.CompareStrategy;
import org.gradle.internal.execution.history.changes.PropertyDiffListener;
import org.gradle.internal.execution.history.changes.SortedMapDiffUtil;
import org.gradle.internal.execution.history.changes.TrivialChangeDetector;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.MissingFileSnapshot;
import org.gradle.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import org.gradle.internal.snapshot.SnapshotUtil;
import org.gradle.internal.snapshot.SnapshotVisitResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;

public class OutputFileChanges implements ChangeContainer {

    private static final CompareStrategy.ChangeFactory<FileSystemLocationSnapshot>
            SNAPSHOT_CHANGE_FACTORY = new CompareStrategy.ChangeFactory<FileSystemLocationSnapshot>() {
        @Override
        public Change added(String path, String propertyTitle, FileSystemLocationSnapshot current) {
            return new DescriptiveChange("Output property '%s' file %s has been added.", propertyTitle, path);
        }

        @Override
        public Change removed(String path, String propertyTitle, FileSystemLocationSnapshot previous) {
            return new DescriptiveChange("Output property '%s' file %s has been removed.", propertyTitle, path);
        }

        @Override
        public Change modified(String path, String propertyTitle, FileSystemLocationSnapshot previous, FileSystemLocationSnapshot current) {
            return new DescriptiveChange("Output property '%s' file %s has changed.", propertyTitle, path);
        }
    };

    private static final TrivialChangeDetector.ItemComparator<FileSystemLocationSnapshot>
            SNAPSHOT_COMPARATOR = new TrivialChangeDetector.ItemComparator<FileSystemLocationSnapshot>() {
        @Override
        public boolean hasSamePath(FileSystemLocationSnapshot previous, FileSystemLocationSnapshot current) {
            return previous.getAbsolutePath().equals(current.getAbsolutePath());
        }

        @Override
        public boolean hasSameContent(FileSystemLocationSnapshot previous, FileSystemLocationSnapshot current) {
            return previous.isContentUpToDate(current);
        }
    };

    @VisibleForTesting
    static final CompareStrategy<FileSystemSnapshot, FileSystemLocationSnapshot> COMPARE_STRATEGY = new CompareStrategy<>(
            OutputFileChanges::index,
            SnapshotUtil::getRootHashes,
            new TrivialChangeDetector<>(
                    SNAPSHOT_COMPARATOR,
                    SNAPSHOT_CHANGE_FACTORY,
                    new AbsolutePathChangeDetector<>(
                            FileSystemLocationSnapshot::isContentUpToDate,
                            SNAPSHOT_CHANGE_FACTORY
                    )
            )
    );

    private final SortedMap<String, FileSystemSnapshot> previous;
    private final SortedMap<String, FileSystemSnapshot> current;

    public OutputFileChanges(SortedMap<String, FileSystemSnapshot> previous, SortedMap<String, FileSystemSnapshot> current) {
        this.previous = previous;
        this.current = current;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        return SortedMapDiffUtil
                .diff(previous, current, new PropertyDiffListener<String, FileSystemSnapshot, FileSystemSnapshot>() {
            @Override
            public boolean removed(String previousProperty) {
                return true;
            }

            @Override
            public boolean added(String currentProperty) {
                return true;
            }

            @Override
            public boolean updated(String property, FileSystemSnapshot previous, FileSystemSnapshot current) {
                return COMPARE_STRATEGY.visitChangesSince(previous, current, property, visitor);
            }
        });
    }

    private static Map<String, FileSystemLocationSnapshot> index(FileSystemSnapshot snapshot) {
        Map<String, FileSystemLocationSnapshot> index = new LinkedHashMap<>();
        snapshot.accept(new RootTrackingFileSystemSnapshotHierarchyVisitor() {
            @Override
            public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot) {
                // Remove missing roots so they show up as added/removed instead of changed
                if (!(isRoot && snapshot instanceof MissingFileSnapshot)) {
                    index.put(snapshot.getAbsolutePath(), snapshot);
                }
                return SnapshotVisitResult.CONTINUE;
            }
        });
        return index;
    }
}