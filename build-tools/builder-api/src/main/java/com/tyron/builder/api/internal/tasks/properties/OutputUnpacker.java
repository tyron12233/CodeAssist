package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.MutableBoolean;
import com.tyron.builder.api.internal.file.FileCollectionFactory;

public class OutputUnpacker extends PropertyVisitor.Adapter {

    private final String ownerDisplayName;
    private final FileCollectionFactory fileCollectionFactory;
    private final boolean locationOnly;
    private final UnpackedOutputConsumer unpackedOutputConsumer;
    private final boolean finalizeBeforeUnpacking;
    private boolean hasDeclaredOutputs;

    public OutputUnpacker(String ownerDisplayName, FileCollectionFactory fileCollectionFactory, boolean locationOnly, boolean finalizeBeforeUnpacking, UnpackedOutputConsumer unpackedOutputConsumer) {
        this.ownerDisplayName = ownerDisplayName;
        this.fileCollectionFactory = fileCollectionFactory;
        this.locationOnly = locationOnly;
        this.finalizeBeforeUnpacking = finalizeBeforeUnpacking;
        this.unpackedOutputConsumer = unpackedOutputConsumer;
    }

    public interface UnpackedOutputConsumer {
        void visitUnpackedOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertySpec spec);
        void visitEmptyOutputFileProperty(String propertyName, boolean optional, PropertyValue value);

        static UnpackedOutputConsumer composite(UnpackedOutputConsumer consumer1, UnpackedOutputConsumer consumer2) {
            return new UnpackedOutputConsumer() {
                @Override
                public void visitUnpackedOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertySpec spec) {
                    consumer1.visitUnpackedOutputFileProperty(propertyName, optional, value, spec);
                    consumer2.visitUnpackedOutputFileProperty(propertyName, optional, value, spec);
                }

                @Override
                public void visitEmptyOutputFileProperty(String propertyName, boolean optional, PropertyValue value) {
                    consumer1.visitEmptyOutputFileProperty(propertyName, optional, value);
                    consumer2.visitEmptyOutputFileProperty(propertyName, optional, value);
                }
            };
        }
    }

    @Override
    public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
        hasDeclaredOutputs = true;
        MutableBoolean hasSpecs = new MutableBoolean();
        if (finalizeBeforeUnpacking) {
            value.maybeFinalizeValue();
        }
        FileParameterUtils.resolveOutputFilePropertySpecs(ownerDisplayName, propertyName, value, filePropertyType, fileCollectionFactory, locationOnly, spec -> {
            hasSpecs.set(true);
            unpackedOutputConsumer.visitUnpackedOutputFileProperty(propertyName, optional, value, spec);
        });
        if (!hasSpecs.get()) {
            unpackedOutputConsumer.visitEmptyOutputFileProperty(propertyName, optional, value);
        }
    }

    public boolean hasDeclaredOutputs() {
        return hasDeclaredOutputs;
    }
}