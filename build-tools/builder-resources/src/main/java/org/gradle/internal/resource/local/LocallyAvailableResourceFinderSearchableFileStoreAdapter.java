package org.gradle.internal.resource.local;

import org.gradle.internal.hash.ChecksumService;

import java.util.stream.Collectors;

/**
 * Makes a LocallyAvailableResourceFinder out of a FileStoreSearcher.
 * @param <C> The type of criterion the filestore can be searched for, and therefore locally available resources searched for.
 */
public class LocallyAvailableResourceFinderSearchableFileStoreAdapter<C> extends AbstractLocallyAvailableResourceFinder<C> {

    public LocallyAvailableResourceFinderSearchableFileStoreAdapter(final FileStoreSearcher<C> fileStore, ChecksumService checksumService) {
        super(criterion -> () -> {
            return fileStore.search(criterion).stream().map(LocallyAvailableResource::getFile).collect(Collectors.toList());
        }, checksumService);
    }

}
