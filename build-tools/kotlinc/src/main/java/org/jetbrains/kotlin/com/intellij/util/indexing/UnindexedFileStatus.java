package org.jetbrains.kotlin.com.intellij.util.indexing;

public class UnindexedFileStatus {
    private final boolean shouldIndex;
    private final boolean indexesWereProvidedByStructureExtension;
    private final long timeProcessingUpToDateFiles;
    private final long timeUpdatingContentLessIndexes;
    private final long timeIndexingWithoutContent;

    public UnindexedFileStatus(boolean shouldIndex,
                               boolean indexesWereProvidedByStructureExtension,
                               long timeProcessingUpToDateFiles,
                               long timeUpdatingContentLessIndexes,
                               long timeIndexingWithoutContent) {
        this.shouldIndex = shouldIndex;
        this.indexesWereProvidedByStructureExtension = indexesWereProvidedByStructureExtension;
        this.timeProcessingUpToDateFiles = timeProcessingUpToDateFiles;
        this.timeUpdatingContentLessIndexes = timeUpdatingContentLessIndexes;
        this.timeIndexingWithoutContent = timeIndexingWithoutContent;
    }

    public boolean wasFullyIndexedByInfrastructureExtension() {
        return !shouldIndex && indexesWereProvidedByStructureExtension;
    }

    public boolean isShouldIndex() {
        return shouldIndex;
    }

    public boolean isIndexesWereProvidedByStructureExtension() {
        return indexesWereProvidedByStructureExtension;
    }

    public long getTimeIndexingWithoutContent() {
        return timeIndexingWithoutContent;
    }

    public long getTimeProcessingUpToDateFiles() {
        return timeProcessingUpToDateFiles;
    }

    public long getTimeUpdatingContentLessIndexes() {
        return timeUpdatingContentLessIndexes;
    }
}
