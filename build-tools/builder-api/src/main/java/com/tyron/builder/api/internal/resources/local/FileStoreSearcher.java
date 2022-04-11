package com.tyron.builder.api.internal.resources.local;

import java.util.Set;

public interface FileStoreSearcher<S> {

    Set<? extends LocallyAvailableResource> search(S key);

}