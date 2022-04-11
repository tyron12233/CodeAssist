package com.tyron.builder.api.tasks.util;

import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.util.Predicates;

import java.util.function.Predicate;

public class IntersectionPatternSet extends PatternSet {

    private final PatternSet other;

    public IntersectionPatternSet(PatternSet other) {
        super(other);
        this.other = other;
    }

    public PatternSet getOther() {
        return other;
    }

    @Override
    public Predicate<FileTreeElement> getAsSpec() {
        return Predicates.intersect(super.getAsSpec(), other.getAsSpec());
    }


    @Override
    public boolean isEmpty() {
        return other.isEmpty() && super.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        IntersectionPatternSet that = (IntersectionPatternSet) o;

        return other != null ? other.equals(that.other) : that.other == null;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + (other != null ? other.hashCode() : 0);
        return result;
    }
}