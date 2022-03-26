package com.tyron.builder.api.execution.plan;

import java.util.ArrayList;
import java.util.List;

public class FailureCollector {

    private final List<Throwable> failures = new ArrayList<>();

    public void addFailure(Throwable throwable) {
        failures.add(throwable);
    }

    public List<Throwable> getFailures() {
        return failures;
    }

    public void clearFailures() {
        failures.clear();
    }
}