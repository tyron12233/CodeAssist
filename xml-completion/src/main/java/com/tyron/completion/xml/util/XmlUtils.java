package com.tyron.completion.xml.util;

import android.util.Pair;

import com.tyron.completion.xml.lexer.XMLLexer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

public class XmlUtils {

    private static final XmlPullParserFactory sParserFactory;

    static {
        XmlPullParserFactory sParserFactory1;
        try {
            sParserFactory1 = XmlPullParserFactory.newInstance();
        } catch (XmlPullParserException e) {
            sParserFactory1 = null;
            e.printStackTrace();
        }
        sParserFactory = sParserFactory1;
    }

    public static XmlPullParser newPullParser() throws XmlPullParserException {
        return sParserFactory.newPullParser();
    }

    public static String getAttributeValueFromPrefix(String prefix) {
        String attributeValue = prefix;
        if (attributeValue.contains("=")) {
            attributeValue = attributeValue.substring(attributeValue.indexOf('=') + 1);
        }
        if (attributeValue.startsWith("\"")) {
            attributeValue = attributeValue.substring(1);
        }
        if (attributeValue.endsWith("\"")) {
            attributeValue = attributeValue.substring(0, attributeValue.length() - 1);
        }
        return attributeValue;
    }


    public static String getAttributeNameFromPrefix(String prefix) {
        String attributeName = prefix;
        if (attributeName.contains("=")) {
            attributeName = prefix.substring(0, prefix.indexOf('='));
        }
        if (prefix.contains(":")) {
            attributeName = attributeName.substring(attributeName.indexOf(':') + 1);
        }
        return attributeName;
    }

    /**
     * @return pair of the parent tag and the current tag at the current position
     */
    public static Pair<String, String> getTagAtPosition(XmlPullParser parser, int line, int column) {
        int lineNumber = parser.getLineNumber();
        int previousDepth = parser.getDepth();
        String previousTag = "";
        String parentTag = "";
        String tag = parser.getName();
        while (lineNumber < line) {
            previousTag = parser.getName();
            try {
                parser.nextTag();
            } catch (Throwable e) {
                // ignored, keep parsing
            }
            lineNumber = parser.getLineNumber();

            if (parser.getName() != null) {
                tag = parser.getName();
            }

            if (parser.getDepth() > previousDepth) {
                previousDepth = parser.getDepth();
                parentTag = previousTag;
            }
        }

        if (parentTag == null && previousTag != null) {
            parentTag = previousTag;
        }

        return Pair.create(parentTag, tag);
    }

    /**
     * @return whether the current index is inside an attribute value,
     * e.g {@code attribute="CURSOR"}
     */
    public static boolean isInAttributeValue(String contents, int index) {
        XMLLexer lexer = new XMLLexer(CharStreams.fromString(contents));
        Token token;
        while ((token = lexer.nextToken()) != null) {
            int start = token.getStartIndex();
            int end = token.getStopIndex();

            if (start <= index && index <= end) {
                return token.getType() == XMLLexer.STRING;
            }

            if (end > index) {
                break;
            }
        }
        return false;
    }
}
