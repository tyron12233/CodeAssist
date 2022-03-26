package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.tasks.FileNormalizer;

import org.jetbrains.annotations.Nullable;

public class CompositePropertyVisitor implements PropertyVisitor {
    private final PropertyVisitor[] visitors;

    public CompositePropertyVisitor(PropertyVisitor... visitors) {
        this.visitors = visitors;
    }

    @Override
    public void visitInputFileProperty(
            String propertyName,
            boolean optional,
            boolean skipWhenEmpty,
            DirectorySensitivity directorySensitivity,
            LineEndingSensitivity lineEndingSensitivity,
            boolean incremental,
            @Nullable Class<? extends FileNormalizer> fileNormalizer,
            PropertyValue value,
            InputFilePropertyType filePropertyType
    ) {
        for (PropertyVisitor visitor : visitors) {
            visitor.visitInputFileProperty(
                    propertyName,
                    optional,
                    skipWhenEmpty,
                    directorySensitivity,
                    lineEndingSensitivity,
                    incremental,
                    fileNormalizer,
                    value,
                    filePropertyType
            );
        }
    }

    @Override
    public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
        for (PropertyVisitor visitor : visitors) {
            visitor.visitInputProperty(propertyName, value, optional);
        }
    }

    @Override
    public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
        for (PropertyVisitor visitor : visitors) {
            visitor.visitOutputFileProperty(propertyName, optional, value, filePropertyType);
        }
    }

    @Override
    public void visitDestroyableProperty(Object value) {
        for (PropertyVisitor visitor : visitors) {
            visitor.visitDestroyableProperty(value);
        }
    }

    @Override
    public void visitLocalStateProperty(Object value) {
        for (PropertyVisitor visitor : visitors) {
            visitor.visitLocalStateProperty(value);
        }
    }
}