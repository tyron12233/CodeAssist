package com.tyron.builder.api.internal.artifacts.repositories;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.NamedDomainObjectCollection;
import com.tyron.builder.api.artifacts.repositories.ArtifactRepository;

public interface ArtifactRepositoryInternal extends ArtifactRepository, Describable {

    void onAddToContainer(NamedDomainObjectCollection<ArtifactRepository> container);

    RepositoryContentDescriptorInternal createRepositoryDescriptor();

    RepositoryContentDescriptorInternal getRepositoryDescriptorCopy();
}
