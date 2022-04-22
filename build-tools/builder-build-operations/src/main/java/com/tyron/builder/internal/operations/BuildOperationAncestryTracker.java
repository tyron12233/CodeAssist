package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public interface BuildOperationAncestryTracker {
    Optional<OperationIdentifier> findClosestMatchingAncestor(@Nullable OperationIdentifier id, Predicate<? super OperationIdentifier> predicate);

    <T> Optional<T> findClosestExistingAncestor(@Nullable OperationIdentifier id, Function<? super OperationIdentifier, T> lookupFunction);
}
