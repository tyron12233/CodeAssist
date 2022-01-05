package com.tyron.layoutpreview.resource;

import android.util.Pair;

import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.tyron.layoutpreview.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class ResourceStyleParser {

    public static Pair<String, Style> parseStyleTag(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "style");

        String name = null;
        String parent = null;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String attributeName = parser.getAttributeName(i);
            String attributeValue = parser.getAttributeValue(i);
            if ("name".equals(attributeName)) {
                name = attributeValue;
            } else if ("parent".equals(attributeName)) {
                parent = attributeValue;
            }

        }

        Style style = new Style(name, parent);
        if (parser.next() != XmlPullParser.END_TAG) {
            parseChildren(style, parser);
        }

        parser.require(XmlPullParser.END_TAG, null, "style");
        return Pair.create(name, style);
    }

    private static void parseChildren(Style style, XmlPullParser parser) throws IOException,
            XmlPullParserException {
        int type;
        final int depth = parser.getDepth();
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = parser.getName();
            if ("item".equals(tag)) {
                parseItemTag(style, parser);
            } else {
                XmlUtils.skip(parser);
            }
        }
    }

    private static void parseItemTag(Style style, XmlPullParser parser) throws IOException,
            XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "item");

        String name = null;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String attributeName = parser.getAttributeName(i);
            String attributeValue = parser.getAttributeValue(i);
            if ("name".equals(attributeName)) {
                name = attributeValue;
            }
        }

        String text = XmlUtils.readText(parser);
        if (text.contains("@")) {
            text = text.substring(text.indexOf("@"));
        }
        style.addValue(name, text.trim());
        parser.require(XmlPullParser.END_TAG, null, "item");
    }

}
