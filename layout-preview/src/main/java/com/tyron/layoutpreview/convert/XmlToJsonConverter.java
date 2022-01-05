package com.tyron.layoutpreview.convert;

import android.util.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XmlToJsonConverter {

    private final Set<String> mAttributesToSkip = new HashSet<>();

    private final String[] ANDROID_WIDGET = new String[] {"TextView", "Button", "ImageView",
            "ImageButton", "LinearLayout", "FrameLayout", "AbsoluteLayout", "RelativeLayout", "ListView",
            "ScrollView", "HorizontalScrollView", "CheckBox", "RatingBar", "ProgressBar", "HorizontalProgressBar",
            "EditText", "AbsListView", "Spinner"};

    private final String[] ANDROID_VIEW = new String[] {"View", "ViewGroup"};

    public XmlToJsonConverter() {

    }

    /**
     * Main entry point for the converter
     * @param contents The xml string to parse
     * @return The JsonObject parsed from XML
     * @throws IOException if an error has occurred while reading the string content
     * @throws XmlPullParserException if the XML content is malformed
     * @throws ConvertException if the XML cannot be converted to JSON
     */
    public JsonObject convert(String contents) throws IOException, XmlPullParserException, ConvertException {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(contents));
        advanceToRootNode(parser);

        return convert(parser);
    }

    public JsonObject convert(File file ) throws IOException, XmlPullParserException, ConvertException {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new InputStreamReader(new FileInputStream(file)));
        advanceToRootNode(parser);

        return convert(parser);
    }

    public JsonObject convert(XmlPullParser parser) throws IOException, XmlPullParserException, ConvertException {

        JsonObject json = parseXmlElement(parser);

        JsonArray children = new JsonArray();
        List<JsonObject> parsedChildren = rConvert(parser);
        parsedChildren.forEach(children::add);

        if (children.size() > 0) {
            json.add("children", children);
        }
        return json;
    }

    /**
     * Used to get the children of the current xml, it is then added to the {@code children}
     * attribute of the parent
     */
    public List<JsonObject> rConvert(XmlPullParser parser) throws IOException, XmlPullParserException, ConvertException {
        List<JsonObject> children = new ArrayList<>();
        final int depth = parser.getDepth();
        int type;

        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            children.add(convert(parser));
        }

        return children;
    }

    /**
     * Parses the current xml element at the position with its attributes and type
     */
    private JsonObject parseXmlElement(XmlPullParser parser) {
        JsonObject json = new JsonObject();
        List<Pair<String, String>> attributes = new ArrayList<>();
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            attributes.add(Pair.create(parser.getAttributeName(i), parser.getAttributeValue(i)));
        }

        String tag = parser.getName();

        json.addProperty("type", tag);

        for (Pair<String, String> attribute : attributes) {
            if (mAttributesToSkip.contains(attribute.first)) {
                continue;
            }
            String name = attribute.first;
            String value = attribute.second;

//            if (name.startsWith(ANDROID_NS_PREFIX)) {
//                name = name.substring(ANDROID_NS_PREFIX.length() + 1); // android:
//            }

//            if (value.startsWith(INTEGER_PREFIX)) {
//                value = value.substring(INTEGER_PREFIX.length());
//            } else if (value.startsWith(ID_PREFIX)) {
//                value = value.substring(ID_PREFIX.length());
//            } else if (value.startsWith(ID_NEW_PREFIX)) {
//                value = value.substring(ID_NEW_PREFIX.length());
//            }

            json.addProperty(name, value);
        }

        return json;
    }

    /**
     * Advances the given parser to the first START_TAG. Throws ConvertException if no start tag is
     * found.
     */
    private void advanceToRootNode(XmlPullParser parser) throws IOException, XmlPullParserException, ConvertException {
        // Look for the root node
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG &&
                type != XmlPullParser.END_DOCUMENT) {
            // Empty
        }

        if (type != XmlPullParser.START_TAG) {
            throw new ConvertException(parser.getPositionDescription()
                    + ": No start tag found!");
        }
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }
}
