package com.tyron.builder.internal.execution.fingerprint;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.api.tasks.FileNormalizer;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface InputFingerprinter {
    Result fingerprintInputProperties(
            ImmutableSortedMap<String, ValueSnapshot> previousValueSnapshots,
            ImmutableSortedMap<String, ? extends FileCollectionFingerprint> previousFingerprints,
            ImmutableSortedMap<String, ValueSnapshot> knownCurrentValueSnapshots,
            ImmutableSortedMap<String, CurrentFileCollectionFingerprint> knownCurrentFingerprints,
            Consumer<InputVisitor> inputs
    ) throws InputFingerprintingException, InputFileFingerprintingException;

    /**
     * Hack require to get normalized input path without fingerprinting contents.
     */
    FileCollectionFingerprinterRegistry getFingerprinterRegistry();

    interface InputVisitor {
        default void visitInputProperty(
                String propertyName,
                ValueSupplier value
        ) {}

        default void visitInputFileProperty(
                String propertyName,
                InputPropertyType type,
                FileValueSupplier value
        ) {}

    }

    enum InputPropertyType {
        /**
         * Non-incremental inputs.
         */
        NON_INCREMENTAL(false, false),

        /**
         * Incremental inputs.
         */
        INCREMENTAL(true, false),

        /**
         * These are the primary inputs to the incremental work item;
         * if they are empty the work item shouldn't be executed.
         */
        PRIMARY(true, true);

        private final boolean incremental;
        private final boolean skipWhenEmpty;

        InputPropertyType(boolean incremental, boolean skipWhenEmpty) {
            this.incremental = incremental;
            this.skipWhenEmpty = skipWhenEmpty;
        }

        public boolean isIncremental() {
            return incremental;
        }

        public boolean isSkipWhenEmpty() {
            return skipWhenEmpty;
        }
    }

    interface ValueSupplier {
        @Nullable
        Object getValue();
    }

    class FileValueSupplier implements ValueSupplier {
        private final Object value;
        private final Class<? extends FileNormalizer> normalizer;
        private final DirectorySensitivity directorySensitivity;
        private final LineEndingSensitivity lineEndingSensitivity;
        private final Supplier<FileCollection> files;

        public FileValueSupplier(
                @Nullable Object value,
                Class<? extends FileNormalizer> normalizer,
                DirectorySensitivity directorySensitivity,
                LineEndingSensitivity lineEndingSensitivity,
                Supplier<FileCollection> files
        ) {
            this.value = value;
            this.normalizer = normalizer;
            this.directorySensitivity = directorySensitivity;
            this.lineEndingSensitivity = lineEndingSensitivity;
            this.files = files;
        }

        @Nullable
        @Override
        public Object getValue() {
            return value;
        }

        public Class<? extends FileNormalizer> getNormalizer() {
            return normalizer;
        }

        public DirectorySensitivity getDirectorySensitivity() {
            return directorySensitivity;
        }

        public LineEndingSensitivity getLineEndingNormalization() {
            return lineEndingSensitivity;
        }

        public FileCollection getFiles() {
            return files.get();
        }
    }

    interface Result {
        /**
         * Returns the values snapshotted just now.
         */
        ImmutableSortedMap<String, ValueSnapshot> getValueSnapshots();

        /**
         * Returns all the value snapshots, including previously known ones.
         */
        ImmutableSortedMap<String, ValueSnapshot> getAllValueSnapshots();

        /**
         * Returns the files fingerprinted just now.
         */
        ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getFileFingerprints();

        /**
         * Returns all the file fingerprints, including the previously known ones.
         */
        ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getAllFileFingerprints();

        /**
         * Returns the file property names which need an isEmpty() check when used with {@link org.gradle.api.tasks.SkipWhenEmpty}.
         *
         * Archive file trees backed by a file need the isEmpty() check, since the fingerprint will be the backing file.
         */
        ImmutableSet<String> getPropertiesRequiringIsEmptyCheck();
    }

    class InputFingerprintingException extends RuntimeException {
        private final String propertyName;

        public InputFingerprintingException(String propertyName, String message, Throwable cause) {
            super(String.format("Cannot fingerprint input property '%s': %s.", propertyName, message), cause);
            this.propertyName = propertyName;
        }

        public String getPropertyName() {
            return propertyName;
        }
    }

    class InputFileFingerprintingException extends RuntimeException {
        private final String propertyName;

        public InputFileFingerprintingException(String propertyName, Throwable cause) {
            super(String.format("Cannot fingerprint input file property '%s': %s", propertyName, cause.getMessage()), cause);
            this.propertyName = propertyName;
        }

        private InputFileFingerprintingException(String formattedMessage, Throwable cause, String propertyName) {
            super(formattedMessage, cause);
            this.propertyName = propertyName;
        }

        public String getPropertyName() {
            return propertyName;
        }
    }
}