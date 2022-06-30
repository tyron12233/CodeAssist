package com.tyron.builder.internal.html;

import com.tyron.builder.internal.xml.SimpleMarkupWriter;

import java.io.IOException;
import java.io.Writer;

/**
 * <p>A streaming HTML writer.</p>
 */
public class SimpleHtmlWriter extends SimpleMarkupWriter {

    public SimpleHtmlWriter(Writer writer) throws IOException {
        this(writer, null);
    }

    public SimpleHtmlWriter(Writer writer, String indent) throws IOException {
        super(writer, indent);
        writeHtmlHeader();
    }

    private void writeHtmlHeader() throws IOException {
        writeRaw("<!DOCTYPE html>");
    }
}
