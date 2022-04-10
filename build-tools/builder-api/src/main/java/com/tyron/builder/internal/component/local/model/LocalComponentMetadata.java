package com.tyron.builder.internal.component.local.model;

import javax.annotation.Nullable;

public interface LocalComponentMetadata extends ComponentResolveMetadata {
    @Nullable
//    @Override
    LocalConfigurationMetadata getConfiguration(String name);
}
