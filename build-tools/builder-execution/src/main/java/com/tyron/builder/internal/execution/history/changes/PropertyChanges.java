package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.Describable;

import com.google.common.collect.Sets;
import com.tyron.builder.internal.execution.history.DescriptiveChange;

import java.util.stream.Stream;

public class PropertyChanges implements ChangeContainer {

    private final ImmutableSortedSet<String> previous;
    private final ImmutableSortedSet<String> current;
    private final String title;
    private final Describable executable;

    public PropertyChanges(
            ImmutableSortedSet<String> previous,
            ImmutableSortedSet<String> current,
            String title,
            Describable executable
    ) {
        this.previous = previous;
        this.current = current;
        this.title = title;
        this.executable = executable;
    }

    @Override
    public boolean accept(ChangeVisitor visitor) {
        if (previous.equals(current)) {
            return true;
        }
        Stream<DescriptiveChange> removedProperties = Sets.difference(previous, current).stream()
                .map(removedProperty -> new DescriptiveChange("%s property '%s' has been removed for %s",
                        title, removedProperty, executable.getDisplayName()));
        Stream<DescriptiveChange> addedProperties = Sets.difference(current, previous).stream()
                .map(addedProperty -> new DescriptiveChange("%s property '%s' has been added for %s",
                        title, addedProperty, executable.getDisplayName()));
        return Stream.concat(removedProperties, addedProperties)
                .map(visitor::visitChange)
                .filter(shouldContinue -> !shouldContinue)
                .findFirst()
                .orElse(true);
    }
}