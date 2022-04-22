package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.internal.execution.history.changes.CompareStrategy.ChangeDetector;
import com.tyron.builder.internal.execution.history.changes.CompareStrategy.ChangeFactory;

import java.util.Map;

/**
 * Compares collections if either current or previous are empty, or both current and previous have one element.
 */
public class TrivialChangeDetector<S> implements ChangeDetector<S> {
    private final ItemComparator<S> itemComparator;
    private final ChangeFactory<S> changeFactory;
    private final ChangeDetector<S> delegate;

    public TrivialChangeDetector(
            ItemComparator<S> itemComparator,
            ChangeFactory<S> changeFactory,
            ChangeDetector<S> delegate
    ) {
        this.itemComparator = itemComparator;
        this.changeFactory = changeFactory;
        this.delegate = delegate;
    }

    @Override
    public boolean visitChangesSince(Map<String, S> previous, Map<String, S> current, String propertyTitle, ChangeVisitor visitor) {
        switch (current.size()) {
            case 0:
                if (previous.isEmpty()) {
                    return true;
                }
                for (Map.Entry<String, S> entry : previous.entrySet()) {
                    if (!visitor.visitChange(changeFactory.removed(entry.getKey(), propertyTitle, entry.getValue()))) {
                        return false;
                    }
                }
                return true;

            case 1:
                switch (previous.size()) {
                    case 0:
                        return reportAllAdded(visitor, current, propertyTitle);
                    case 1:
                        Map.Entry<String, S> previousEntry = previous.entrySet().iterator().next();
                        Map.Entry<String, S> currentEntry = current.entrySet().iterator().next();
                        return compareTrivialEntries(visitor, previousEntry, currentEntry, propertyTitle);
                    default:
                        return delegate.visitChangesSince(previous, current, propertyTitle, visitor);
                }

            default:
                if (!previous.isEmpty()) {
                    return delegate.visitChangesSince(previous, current, propertyTitle, visitor);
                }
                return reportAllAdded(visitor, current, propertyTitle);
        }
    }

    private boolean reportAllAdded(ChangeVisitor visitor, Map<String, S> current, String propertyTitle) {
        for (Map.Entry<String, S> entry : current.entrySet()) {
            if (!visitor.visitChange(changeFactory.added(entry.getKey(), propertyTitle, entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private boolean compareTrivialEntries(ChangeVisitor visitor, Map.Entry<String, S> previousEntry, Map.Entry<String, S> currentEntry, String propertyTitle) {
        S previous = previousEntry.getValue();
        S current = currentEntry.getValue();
        if (itemComparator.hasSamePath(previous, current)) {
            if (itemComparator.hasSameContent(previous, current)) {
                return true;
            }
            String path = currentEntry.getKey();
            return visitor.visitChange(changeFactory.modified(path, propertyTitle, previous, current));
        } else {
            if (visitor.visitChange(changeFactory.removed(previousEntry.getKey(), propertyTitle, previous))) {
                return visitor.visitChange(changeFactory.added(currentEntry.getKey(), propertyTitle, current));
            }
            return false;
        }
    }

    public interface ItemComparator<S> {
        boolean hasSamePath(S previous, S current);

        boolean hasSameContent(S previous, S current);
    }
}