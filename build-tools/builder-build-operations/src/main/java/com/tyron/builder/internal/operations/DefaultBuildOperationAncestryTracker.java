package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

public class DefaultBuildOperationAncestryTracker implements BuildOperationListener, BuildOperationAncestryTracker {

    private final Map<OperationIdentifier, OperationIdentifier> parents = new ConcurrentHashMap<OperationIdentifier, OperationIdentifier>();

    @Override
    public Optional<OperationIdentifier> findClosestMatchingAncestor(@Nullable OperationIdentifier id, Predicate<? super OperationIdentifier> predicate) {
        if (id == null) {
            return Optional.empty();
        }
        if (predicate.test(id)) {
            return Optional.of(id);
        }
        return findClosestMatchingAncestor(parents.get(id), predicate);
    }

    @Override
    public <T> Optional<T> findClosestExistingAncestor(@Nullable OperationIdentifier id, Function<? super OperationIdentifier, T> lookupFunction) {
        if (id == null) {
            return Optional.empty();
        }
        T value = lookupFunction.apply(id);
        if (value != null) {
            return Optional.of(value);
        }
        return findClosestExistingAncestor(parents.get(id), lookupFunction);
    }

    @Override
    public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        if (buildOperation.getParentId() != null) {
            parents.put(buildOperation.getId(), buildOperation.getParentId());
        }
    }

    @Override
    public void progress(OperationIdentifier operationIdentifier, OperationProgressEvent progressEvent) {
    }

    @Override
    public void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
        parents.remove(buildOperation.getId());
    }
}