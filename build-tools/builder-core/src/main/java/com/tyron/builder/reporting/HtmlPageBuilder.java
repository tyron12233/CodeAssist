package com.tyron.builder.reporting;

import java.net.URL;
import java.util.Date;

public interface HtmlPageBuilder<T> {
    /**
     * Registers a resource that is required by this page.
     *
     * @return A relative URL that refers to the resource's output location.
     */
    String requireResource(URL resource);

    String formatDate(Date date);

    T getOutput();
}
