package com.tyron.builder.api.tasks;

/**
 * A normalizer used to remove unwanted noise when considering file inputs.
 * The default behavior without specifying a normalizer is to ignore the order of the files.
 *
 * @see TaskInputFilePropertyBuilder#withNormalizer(Class)
 *
 * @since 4.3
 */
public interface FileNormalizer {
}