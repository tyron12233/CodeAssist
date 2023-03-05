package com.tyron.builder.common.resources.escape.xml;

import org.jetbrains.annotations.NotNull;
import org.xml.sax.Attributes;
import org.xml.sax.ext.DefaultHandler2;

import java.util.function.Consumer;

final class StringResourceContentHandler extends DefaultHandler2 {

    static final String STRING_ELEMENT_NAME = "string";

    @SuppressWarnings("StringBufferField")
    private final StringBuilder mBuilder;

    private final Consumer<String> mCharacterHandler;

    private boolean mHandlingEntity;

    private boolean mHandlingCdata;

    /**
     * The builder's length at the end of startElement
     */
    private int mStartElementBuilderLength;

    StringResourceContentHandler(
            @NotNull StringBuilder builder, @NotNull Consumer<String> characterHandler) {
        mBuilder = builder;
        mCharacterHandler = characterHandler;
    }

    @Override
    public void characters(char[] chars, int offset, int length) {
        if (mHandlingEntity) {
            // I would reset this bit in endEntity but the SAX parser in tools/base/ calls
            // characters after endEntity. The one in tools/adt/idea/ calls it before.
            mHandlingEntity = false;
            return;
        }

        if (mHandlingCdata) {
            mBuilder.append(chars, offset, length);
        }
        else {
            mCharacterHandler.accept(new String(chars, offset, length));
        }
    }

    @Override
    public void startElement(
            String uri, String localName, String qualifiedName, Attributes attributes) {
        if (qualifiedName.equals(STRING_ELEMENT_NAME)) {
            return;
        }

        mBuilder
                .append('<')
                .append(qualifiedName);

        for (int i = 0, length = attributes.getLength(); i < length; i++) {
            mBuilder
                    .append(' ')
                    .append(attributes.getQName(i))
                    .append("=\"")
                    .append(attributes.getValue(i))
                    .append('"');
        }

        mBuilder.append('>');
        mStartElementBuilderLength = mBuilder.length();
    }

    @Override
    public void endElement(String uri, String localName, String qualifiedName) {
        if (qualifiedName.equals(STRING_ELEMENT_NAME)) {
            return;
        }

        if (mBuilder.length() == mStartElementBuilderLength) {
            // If the builder's length hasn't changed since the last startElement call, the element
            // is empty
            mBuilder.setCharAt(mBuilder.length() - 1, '/');
            mBuilder.append('>');
        }
        else {
            mBuilder
                    .append("</")
                    .append(qualifiedName)
                    .append('>');
        }
    }

    @Override
    public void startEntity(String name) {
        mBuilder
                .append('&')
                .append(name)
                .append(';');

        mHandlingEntity = true;
    }

    @Override
    public void startCDATA() {
        mBuilder.append("<![CDATA[");
        mHandlingCdata = true;
    }

    @Override
    public void endCDATA() {
        mBuilder.append("]]>");
        mHandlingCdata = false;
    }

    @Override
    public void comment(char[] chars, int offset, int length) {
        mBuilder
                .append("<!--")
                .append(chars, offset, length)
                .append("-->");
    }

    @Override
    public void processingInstruction(String target, String data) {
        mBuilder
                .append("<?")
                .append(target)
                .append(' ')
                .append(data)
                .append("?>");
    }
}