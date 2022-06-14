package com.tyron.builder.reporting;

import com.tyron.builder.internal.html.SimpleHtmlWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TabsRenderer<T> extends ReportRenderer<T, SimpleHtmlWriter> {
    private final List<TabDefinition> tabs = new ArrayList<TabDefinition>();

    public void add(String title, ReportRenderer<T, SimpleHtmlWriter> contentRenderer) {
        tabs.add(new TabDefinition(title, contentRenderer));
    }

    public void clear() {
        tabs.clear();
    }

    @Override
    public void render(T model, SimpleHtmlWriter htmlWriterWriter) throws IOException {
        htmlWriterWriter.startElement("div").attribute("id", "tabs");
            htmlWriterWriter.startElement("ul").attribute("class", "tabLinks");
                for (int i = 0; i < this.tabs.size(); i++) {
                    TabDefinition tab = this.tabs.get(i);
                    String tabId = "tab" + i;
                    htmlWriterWriter.startElement("li");
                        htmlWriterWriter.startElement("a").attribute("href", "#" + tabId).characters(tab.title).endElement();
                    htmlWriterWriter.endElement();
                }
            htmlWriterWriter.endElement();

            for (int i = 0; i < this.tabs.size(); i++) {
                TabDefinition tab = this.tabs.get(i);
                String tabId = "tab" + i;
                htmlWriterWriter.startElement("div").attribute("id", tabId).attribute("class", "tab");
                    htmlWriterWriter.startElement("h2").characters(tab.title).endElement();
                    tab.renderer.render(model, htmlWriterWriter);
                htmlWriterWriter.endElement();
            }
        htmlWriterWriter.endElement();
    }

    private class TabDefinition {
        final String title;
        final ReportRenderer<T, SimpleHtmlWriter> renderer;

        private TabDefinition(String title, ReportRenderer<T, SimpleHtmlWriter> renderer) {
            this.title = title;
            this.renderer = renderer;
        }
    }
}
