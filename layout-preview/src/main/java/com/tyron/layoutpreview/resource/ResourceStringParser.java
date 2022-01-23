package com.tyron.layoutpreview.resource;

import static com.tyron.layoutpreview.util.XmlUtils.advanceToRootNode;
import static com.tyron.layoutpreview.util.XmlUtils.readText;
import static com.tyron.layoutpreview.util.XmlUtils.skip;

import android.util.Pair;

import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Value;
import com.tyron.builder.project.api.FileManager;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ResourceStringParser {

    private final File mResourceDir;
    private final FileManager mFileManager;

    public ResourceStringParser(File resourceDir, FileManager fileManager) {
        mResourceDir = resourceDir;
        mFileManager = fileManager;
    }

    public Map<String, Map<String, Value>> getStrings() {
        Map<String, Map<String, Value>> locales = new HashMap<>();
        locales.put(null, getDefaultStrings());
        return locales;
    }

    private Map<String, Value> getDefaultStrings() {
        HashMap<String, Value> strings = new HashMap<>();
        File defaultValues = new File(mResourceDir, "values");
        File[] xmlFiles = defaultValues.listFiles(c -> c.getName().endsWith(".xml"));
        if (xmlFiles == null) {
            return strings;
        }
        for (File file : xmlFiles) {
            Optional<CharSequence> contents = mFileManager.getFileContent(file);
            contents.ifPresent(charSequence ->
                    strings.putAll(parseStringXml(charSequence.toString())));
        }

        return strings;
    }

    public Map<String, Value> parseStringXml(String input) {
        XmlPullParser parser = null;
        try {
            parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(input));
            return doParseStringXml(parser);
        } catch (XmlPullParserException | ConvertException | IOException e) {
            return Collections.emptyMap();
        }
    }

    public Map<String, Value> parseStringXml(InputStream xml) {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new InputStreamReader(xml));
            return doParseStringXml(parser);
        } catch (XmlPullParserException | IOException | ConvertException e) {
            return Collections.emptyMap();
        }
    }

    private Map<String, Value> doParseStringXml(XmlPullParser parser) throws ConvertException, XmlPullParserException, IOException {
        advanceToRootNode(parser);

        HashMap<String, Value> strings = new HashMap<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            String name = parser.getName();
            if (name.equals("string")) {
                Pair<String, Value> pair = parseStringXmlInternal(parser);
                if (pair != null) {
                    strings.put(pair.first, pair.second);
                }
            } else if (name.equals("item")) {
                Pair<String, Value> pair = parseItemString(parser);
                if (pair != null) {
                    strings.put(pair.first, pair.second);
                }
            } else {
                skip(parser);
            }
        }

        return strings;
    }

    public static Pair<String, Value> parseStringXmlInternal(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "string");
        String name = null;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (parser.getAttributeName(i).equals("name")) {
                name = parser.getAttributeValue(i);
            }
        }
        String text = readText(parser);
        parser.require(XmlPullParser.END_TAG, null, "string");

        return Pair.create(name, new Primitive(text));
    }

    public static Pair<String, Value> parseItemString(XmlPullParser parser) throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, "item");
        String name = null;
        String type = null;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (parser.getAttributeName(i).equals("name")) {
                name = parser.getAttributeValue(i);
            }
            if (parser.getAttributeName(i).equals("type")) {
               type = parser.getAttributeValue(i);
            }
        }
        if (type == null) {
            XmlUtils.skip(parser);
        } else {
            String text = readText(parser);
            parser.require(XmlPullParser.END_TAG, null, "item");
            return Pair.create(name, new Primitive(text));
        }
        return null;
    }
}
