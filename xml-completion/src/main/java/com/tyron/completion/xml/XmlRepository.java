package com.tyron.completion.xml;

import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.Decompress;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.Format;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class XmlRepository {

    private File mAttrsFile;
    private Map<String, DeclareStyleable> mDeclareStyleables;

    public void initialize() {
        mAttrsFile = getOrExtractFiles();
        try {
            mDeclareStyleables = parse(mAttrsFile);
        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, DeclareStyleable> parse(File file) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser parser = factory.newPullParser();
        parser.setInput(new FileReader(file));

        Map<String, DeclareStyleable> declareStyleables = new TreeMap<>();

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if ("declare-styleable".equals(name)) {
                DeclareStyleable declareStyleable = parseDeclareStyleable(parser);
                declareStyleables.put(declareStyleable.getName(), declareStyleable);
            }
        }

        return declareStyleables;
    }

    private DeclareStyleable parseDeclareStyleable(XmlPullParser parser) throws IOException,
            XmlPullParserException {

        String name = getAttributeValue(parser, "name", "");
        Set<AttributeInfo> attributeInfos = new TreeSet<>();

        final int depth = parser.getDepth();
        int type;
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = parser.getName();
            if ("attr".equals(tag)) {
                AttributeInfo attributeInfo = parseAttributeInfo(parser);
                attributeInfos.add(attributeInfo);
            }
        }

        return new DeclareStyleable(name, attributeInfos);
    }

    private AttributeInfo parseAttributeInfo(XmlPullParser parser) throws IOException,
            XmlPullParserException {
        String name = getAttributeValue(parser, "name", "");
        Set<Format> formats = new TreeSet<>();
        List<String> values = new ArrayList<>();

        String formatString = getAttributeValue(parser, "format", null);
        if (formatString != null) {
            formats.addAll(Format.fromString(formatString));
        }

        final int depth = parser.getDepth();
        int type;
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String tag = parser.getName();
            if ("enum".equals(tag)) {
                formats.add(Format.ENUM);
                String enumName = getAttributeValue(parser, "name", null);
                if (enumName != null) {
                    values.add(enumName);
                }
            } else if ("flag".equals(tag)) {
                formats.add(Format.FLAG);
                String flagName = getAttributeValue(parser, "name", null);
                if (flagName != null) {
                    values.add(flagName);
                }
            } else {
                skip(parser);
            }
        }
        return new AttributeInfo(name, formats, values);
    }

    public static String getAttributeValue(XmlPullParser parser, String name, String defaultValue) {
        int attributeCount = parser.getAttributeCount();
        for (int i = 0; i < attributeCount; i++) {
            String attributeName = parser.getAttributeName(i);
            String attributeValue = parser.getAttributeValue(i);

            if (name.equals(attributeName)) {
                return attributeValue;
            }
        }
        return defaultValue;
    }

    public static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
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

    private static File getOrExtractFiles() {
        File filesDir = ApplicationProvider.getApplicationContext().getFilesDir();
        File check = new File(filesDir,
                "sources/android-31/data/res/values/attrs.xml");
        if (check.exists()) {
            return check;
        }
        File dest = new File(filesDir, "sources");
        Decompress.unzipFromAssets(ApplicationProvider.getApplicationContext(),
                "android-xml.zip",
                dest.getAbsolutePath());
        return check;
    }
}
