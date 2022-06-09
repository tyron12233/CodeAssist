package com.tyron.builder.internal.resource.local;

import com.google.common.hash.HashCode;

import java.util.LinkedList;
import java.util.List;

public class CompositeLocallyAvailableResourceFinder<C> implements LocallyAvailableResourceFinder<C> {

    private final List<LocallyAvailableResourceFinder<C>> composites;

    public CompositeLocallyAvailableResourceFinder(List<LocallyAvailableResourceFinder<C>> composites) {
        this.composites = composites;
    }

    @Override
    public LocallyAvailableResourceCandidates findCandidates(C criterion) {
        List<LocallyAvailableResourceCandidates> allCandidates = new LinkedList<LocallyAvailableResourceCandidates>();
        for (LocallyAvailableResourceFinder<C> finder : composites) {
            allCandidates.add(finder.findCandidates(criterion));
        }

        return new CompositeLocallyAvailableResourceCandidates(allCandidates);
    }

    private static class CompositeLocallyAvailableResourceCandidates implements LocallyAvailableResourceCandidates {
        private final List<LocallyAvailableResourceCandidates> allCandidates;

        public CompositeLocallyAvailableResourceCandidates(List<LocallyAvailableResourceCandidates> allCandidates) {
            this.allCandidates = allCandidates;
        }

        @Override
        public boolean isNone() {
            for (LocallyAvailableResourceCandidates candidates : allCandidates) {
                if (!candidates.isNone()) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public LocallyAvailableResource findByHashValue(HashCode hashValue) {
            for (LocallyAvailableResourceCandidates candidates : allCandidates) {
                LocallyAvailableResource match = candidates.findByHashValue(hashValue);
                if (match != null) {
                    return match;
                }
            }

            return null;
        }
    }
}
