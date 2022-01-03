package com.tyron.layoutpreview.resource;

import android.util.Pair;

import com.flipkart.android.proteus.value.Color;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.tyron.layoutpreview.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class ResourceColorParser {

    public static Pair<String, Value> parseColor(XmlPullParser parser) throws IOException,
            XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "color");

        String name = null;
        String parent = null;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String attributeName = parser.getAttributeName(i);
            String attributeValue = parser.getAttributeValue(i);
            if ("name".equals(attributeName)) {
                name = attributeValue;
            }
        }

        String text = XmlUtils.readText(parser);

        Value value;
        if (Color.isColor(text)) {
            value = Color.valueOf(text);
        } else {
            value = new Resource(text);
        }
        parser.require(XmlPullParser.END_TAG, null, "color");
        return Pair.create(name, value);
    }
}
