package org.gradle.internal.resource.local;

import org.gradle.api.Transformer;
import org.gradle.internal.Factory;
import org.gradle.internal.hash.ChecksumService;

import java.io.File;
import java.util.List;

public class AbstractLocallyAvailableResourceFinder<C> implements LocallyAvailableResourceFinder<C> {

    private final Transformer<Factory<List<File>>, C> producer;
    private final ChecksumService checksumService;

    public AbstractLocallyAvailableResourceFinder(Transformer<Factory<List<File>>, C> producer, ChecksumService checksumService) {
        this.producer = producer;
        this.checksumService = checksumService;
    }

    @Override
    public LocallyAvailableResourceCandidates findCandidates(C criterion) {
        return new LazyLocallyAvailableResourceCandidates(producer.transform(criterion), checksumService);
    }

    public ChecksumService getChecksumService() {
        return checksumService;
    }
}
