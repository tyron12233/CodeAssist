package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.tasks.FileNormalizer;

import org.jetbrains.annotations.Nullable;

/**
 * Visits properties of beans which are inputs, outputs, destroyables or local state.
 */
public interface PropertyVisitor {
    void visitInputFileProperty(String propertyName, boolean optional, boolean skipWhenEmpty, DirectorySensitivity directorySensitivity, LineEndingSensitivity lineEndingSensitivity, boolean incremental, @Nullable Class<? extends FileNormalizer> fileNormalizer, PropertyValue value, InputFilePropertyType filePropertyType);

    void visitInputProperty(String propertyName, PropertyValue value, boolean optional);

    void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType);

    void visitDestroyableProperty(Object value);

    void visitLocalStateProperty(Object value);

    class Adapter implements PropertyVisitor {
        @Override
        public void visitInputFileProperty(String propertyName, boolean optional, boolean skipWhenEmpty, DirectorySensitivity directorySensitivity, LineEndingSensitivity lineEndingSensitivity, boolean incremental, @Nullable Class<? extends FileNormalizer> fileNormalizer, PropertyValue value, InputFilePropertyType filePropertyType) {
        }

        @Override
        public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
        }

        @Override
        public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
        }

        @Override
        public void visitDestroyableProperty(Object value) {
        }

        @Override
        public void visitLocalStateProperty(Object value) {
        }
    }
}
