package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;

import java.util.Map;
import java.util.function.Function;

public class CompareStrategy<C, S> {
    private final Function<C, ? extends Map<String, S>> indexer;
    private final Function<C, ? extends Multimap<String, HashCode>> rootHasher;
    private final ChangeDetector<S> changeDetector;

    public CompareStrategy(
            Function<C, ? extends Map<String, S>> indexer,
            Function<C, ? extends Multimap<String, HashCode>> rootHasher,
            ChangeDetector<S> changeDetector
    ) {
        this.indexer = indexer;
        this.rootHasher = rootHasher;
        this.changeDetector = changeDetector;
    }

    public boolean visitChangesSince(C previous, C current, String propertyTitle, ChangeVisitor visitor) {
        if (Iterables.elementsEqual(rootHasher.apply(previous).entries(), rootHasher.apply(current).entries())) {
            return true;
        }
        return changeDetector.visitChangesSince(indexer.apply(previous), indexer.apply(current), propertyTitle, visitor);
    }

    public interface ChangeDetector<S> {
        boolean visitChangesSince(Map<String, S> previous, Map<String, S> current, String propertyTitle, ChangeVisitor visitor);
    }

    public interface ChangeFactory<S> {
        Change added(String path, String propertyTitle, S current);
        Change removed(String path, String propertyTitle, S previous);
        Change modified(String path, String propertyTitle, S previous, S current);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
