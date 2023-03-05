package org.gradle.reporting;

import org.gradle.internal.html.SimpleHtmlWriter;

import java.io.Writer;
import java.net.URL;

public interface HtmlReportBuilder {
    void requireResource(URL resource);

    <T> void renderHtmlPage(String name, T model, ReportRenderer<T, HtmlPageBuilder<SimpleHtmlWriter>> renderer);

    <T> void renderRawHtmlPage(String name, T model, ReportRenderer<T, HtmlPageBuilder<Writer>> renderer);
}
