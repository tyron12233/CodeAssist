package com.tyron.builder.reporting;

import java.io.IOException;

public abstract class ReportRenderer<T, E> {
    /**
     * Renders the report for the given model to the given output.
     */
    public abstract void render(T model, E output) throws IOException;
}