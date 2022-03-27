package com.tyron.builder.api.internal.tasks.properties;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.util.List;

public class OutputFilesCollector implements OutputUnpacker.UnpackedOutputConsumer {
    private final List<OutputFilePropertySpec> specs = Lists.newArrayList();
    private ImmutableSortedSet<OutputFilePropertySpec> fileProperties;

    public ImmutableSortedSet<OutputFilePropertySpec> getFileProperties() {
        if (fileProperties == null) {
            fileProperties = FileParameterUtils.collectFileProperties("output", specs.iterator());
        }
        return fileProperties;
    }

    @Override
    public void visitUnpackedOutputFileProperty(String propertyName,
                                                boolean optional,
                                                PropertyValue value,
                                                OutputFilePropertySpec spec) {
        specs.add(spec);
    }

    @Override
    public void visitEmptyOutputFileProperty(String propertyName, boolean optional,
                                             PropertyValue value) {
    }
}