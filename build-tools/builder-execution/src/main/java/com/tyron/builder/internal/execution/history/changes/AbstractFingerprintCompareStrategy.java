package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;

public abstract class AbstractFingerprintCompareStrategy extends CompareStrategy<FileCollectionFingerprint, FileSystemLocationFingerprint> implements FingerprintCompareStrategy {

    protected static final ChangeFactory<FileSystemLocationFingerprint> FINGERPRINT_CHANGE_FACTORY = new ChangeFactory<FileSystemLocationFingerprint>() {
        @Override
        public Change added(String path, String propertyTitle, FileSystemLocationFingerprint current) {
            return DefaultFileChange.added(path, propertyTitle, current.getType(), current.getNormalizedPath());
        }

        @Override
        public Change removed(String path, String propertyTitle, FileSystemLocationFingerprint previous) {
            return DefaultFileChange.removed(path, propertyTitle, previous.getType(), previous.getNormalizedPath());
        }

        @Override
        public Change modified(String path, String propertyTitle, FileSystemLocationFingerprint previous, FileSystemLocationFingerprint current) {
            return DefaultFileChange.modified(path, propertyTitle, previous.getType(), current.getType(), current.getNormalizedPath());
        }
    };

    private static final TrivialChangeDetector.ItemComparator<FileSystemLocationFingerprint> ITEM_COMPARATOR = new TrivialChangeDetector.ItemComparator<FileSystemLocationFingerprint>() {
        @Override
        public boolean hasSamePath(FileSystemLocationFingerprint previous, FileSystemLocationFingerprint current) {
            return previous.getNormalizedPath().equals(current.getNormalizedPath());
        }

        @Override
        public boolean hasSameContent(FileSystemLocationFingerprint previous, FileSystemLocationFingerprint current) {
            return previous.getNormalizedContentHash().equals(current.getNormalizedContentHash());
        }
    };

    public AbstractFingerprintCompareStrategy(ChangeDetector<FileSystemLocationFingerprint> changeDetector) {
        super(
                FileCollectionFingerprint::getFingerprints,
                FileCollectionFingerprint::getRootHashes,
                new TrivialChangeDetector<>(ITEM_COMPARATOR, FINGERPRINT_CHANGE_FACTORY, changeDetector)
        );
    }
}