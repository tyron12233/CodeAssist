package com.tyron.builder.api;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.util.Predicates;

import java.util.function.Predicate;

public abstract class AbstractTask implements TaskInternal {

    private Predicate<? super Task> onlyIf = Predicates.satisfyAll();

    @Override
    public boolean getImpliesSubProjects() {
        return false;
    }

    @Override
    public Predicate<? super TaskInternal> getOnlyIf() {
        return onlyIf;
    }

    @Override
    public void setOnlyIf(Predicate<? super Task> onlyIfSpec) {
        this.onlyIf = onlyIfSpec;
    }

    @Override
    public void onlyIf(Predicate<? super Task> onlyIfSpec) {
        setOnlyIf(onlyIfSpec);
    }
}
