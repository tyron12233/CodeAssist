package com.tyron.builder.api.internal.artifacts.repositories;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.repositories.RepositoryContentDescriptor;

public interface RepositoryContentDescriptorInternal extends RepositoryContentDescriptor {
    Action<? super ArtifactResolutionDetails> toContentFilter();
    RepositoryContentDescriptorInternal asMutableCopy();
}
