package com.tyron.builder.internal.xml;

import java.io.*;

/**
 * <p>A streaming XML writer.</p>
 */
public class SimpleXmlWriter extends SimpleMarkupWriter {
    /**
     * Constructs a writer with the given output.
     *
     * @param output The output, should be unbuffered, as this class performs buffering
     */
    public SimpleXmlWriter(OutputStream output) throws IOException {
        this(output, null);
    }

    /**
     * Constructs a writer with the given output.
     *
     * @param output The output, should be unbuffered, as this class performs buffering
     */
    public SimpleXmlWriter(OutputStream output, String indent) throws IOException {
        this(new BufferedWriter(new OutputStreamWriter(output, "UTF-8")), indent, "UTF-8");
    }

    /**
     * Constructs a writer with the given output.
     *
     * @param writer The output, should be buffered.
     */
    public SimpleXmlWriter(Writer writer, String indent, String encoding) throws IOException {
        super(writer, indent);
        writeXmlDeclaration(encoding);
    }

    private void writeXmlDeclaration(String encoding) throws IOException {
        writeRaw("<?xml version=\"1.0\" encoding=\"");
        writeRaw(encoding);
        writeRaw("\"?>");
    }
}
