package com.tyron.psi.lookup;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author peter
 */
public abstract class LookupElementWeigher {
    private final String myId;
    private final boolean myNegated;
    private final boolean myPrefixDependent;

    protected LookupElementWeigher(@NonNls String id, boolean negated, boolean dependsOnPrefix) {
        myId = id;
        myNegated = negated;
        myPrefixDependent = dependsOnPrefix;
    }

    protected LookupElementWeigher(@NonNls String id) {
        this(id, false, false);
    }

    public boolean isPrefixDependent() {
        return myPrefixDependent;
    }

    public boolean isNegated() {
        return myNegated;
    }

    @Override
    public String toString() {
        return myId;
    }

    @Nullable
    public Comparable weigh(@NotNull LookupElement element, @NotNull WeighingContext context) {
        return weigh(element);
    }

    @Nullable
    public Comparable weigh(@NotNull LookupElement element) {
        throw new UnsupportedOperationException("weigh not implemented in " + getClass());
    }

}
