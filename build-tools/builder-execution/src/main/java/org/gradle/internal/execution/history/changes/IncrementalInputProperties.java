package org.gradle.internal.execution.history.changes;


import com.google.common.collect.ImmutableSortedMap;
import org.gradle.api.InvalidUserDataException;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;

public interface IncrementalInputProperties {
    String getPropertyNameFor(Object value);
    InputFileChanges nonIncrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current);
    InputFileChanges incrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current);

    IncrementalInputProperties NONE = new IncrementalInputProperties() {
        @Override
        public String getPropertyNameFor(Object value) {
            throw new InvalidUserDataException("Cannot query incremental changes for property " + value + ": No incremental properties declared.");
        }

        @Override
        public InputFileChanges nonIncrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
            return new DefaultInputFileChanges(previous, current);
        }

        @Override
        public InputFileChanges incrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
            return InputFileChanges.EMPTY;
        }
    };

    IncrementalInputProperties ALL = new IncrementalInputProperties() {
        @Override
        public String getPropertyNameFor(Object value) {
            throw new InvalidUserDataException("Cannot query incremental changes for property " + value + ": Requires using 'InputChanges'.");
        }

        @Override
        public InputFileChanges nonIncrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
            return InputFileChanges.EMPTY;
        }

        @Override
        public InputFileChanges incrementalChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
            return new DefaultInputFileChanges(previous, current);
        }
    };
}