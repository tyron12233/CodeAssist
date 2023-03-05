package com.tyron.builder.merge;

/**
 * Exception thrown when more than one file with the same relative path is found in an incremental
 * input for a merge. For example, if an input has two directories and both have file {@code x},
 * then this exception is thrown.
 *
 * <p>This is different from the case where a file with the same relative path exists in different
 * inputs. That is not an error and is handled by the merger, although some implementations of
 * {@link IncrementalFileMergerOutput} may reject this (for example,
 * {@link StreamMergeAlgorithms#acceptOnlyOne()}.
 */
public class DuplicatePathInIncrementalInputException extends RuntimeException {

    /**
     * Creates a new exception.
     *
     * @param description a description of the exception
     */
    public DuplicatePathInIncrementalInputException(String description) {
        super(description);
    }
}
