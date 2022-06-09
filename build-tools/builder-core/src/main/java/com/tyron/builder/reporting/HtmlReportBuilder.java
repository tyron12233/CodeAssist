package com.tyron.builder.reporting;

import com.tyron.builder.internal.html.SimpleHtmlWriter;

import java.io.Writer;
import java.net.URL;

public interface HtmlReportBuilder {
    void requireResource(URL resource);

    <T> void renderHtmlPage(String name, T model, ReportRenderer<T, HtmlPageBuilder<SimpleHtmlWriter>> renderer);

    <T> void renderRawHtmlPage(String name, T model, ReportRenderer<T, HtmlPageBuilder<Writer>> renderer);
}
