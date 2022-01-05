package com.tyron.layoutpreview.resource;

import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ColorManager;
import com.flipkart.android.proteus.StringManager;
import com.flipkart.android.proteus.StyleManager;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

public class ResourceValueParser {

    public Map<String, Value> mStrings = new HashMap<>();
    public Map<String, Style> mStyles = new HashMap<>();
    public Map<String, Value> mColors = new HashMap<>();
    private final StringManager mStringManager = new StringManager() {
        @Override
        public Map<String, Value> getStrings(@Nullable String tag) {
            return mStrings;
        }
    };
    private final StyleManager mStyleManager = new StyleManager() {
        @Nullable
        @Override
        protected Map<String, Style> getStyles() {
            return mStyles;
        }
    };
    private final ColorManager mColorManager = new ColorManager() {
        @Override
        protected Map<String, Value> getColors() {
            return mColors;
        }
    };

    public StringManager getStringManager() {
        return mStringManager;
    }

    public StyleManager getStyleManager() {
        return mStyleManager;
    }

    public ColorManager getColorManager() {
        return mColorManager;
    }

    public void parse(AndroidModule module) {
        File resourcesDir = module.getAndroidResourcesDirectory();
        File valuesDir = new File(resourcesDir, "values");
        File[] children = valuesDir.listFiles(c -> c.getName().endsWith(".xml"));
        if (children != null) {
            parse(children);
        }
    }

    public void parse(@NonNull File[] children, String namePrefix) {
        for (File child : children) {
            try {
                parse(child, namePrefix);
            } catch (XmlPullParserException e) {
                Log.e("ResourceValueParser", "Unable to parse XML", e);
            } catch (IOException e) {
                Log.e("ResourceValueParser", "File error", e);
            }
        }
    }

    private void parse(@NonNull File[] children) {
       parse(children, "");
    }

    public void parse(File file, String namePrefix) throws IOException, XmlPullParserException {
        parse(new InputStreamReader(new FileInputStream(file)), namePrefix);
    }

    public void parse(File file) throws IOException, XmlPullParserException {
        parse(new InputStreamReader(new FileInputStream(file)), "");
    }

    public void parse(String contents) throws IOException, XmlPullParserException {
        parse(new StringReader(contents), "");
    }

    public void parse(Reader reader, String namePrefix) throws IOException, XmlPullParserException {
        if (!namePrefix.isEmpty() && !namePrefix.endsWith(":")) {
            namePrefix = namePrefix + ":";
        }
        XmlPullParser parser = null;
        try {
            parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(reader);

            XmlUtils.advanceToRootNode(parser);

            while (parser.next() != XmlPullParser.END_TAG) {
                if (parser.getEventType() != XmlPullParser.START_TAG) {
                    continue;
                }

                String tag = parser.getName();
                switch (tag) {
                    case "string":
                        parseStringTag(parser, namePrefix);
                        break;
                    case "item":
                        parseItemTag(parser, namePrefix);
                        break;
                    case "style":
                        parseStyleTag(parser, namePrefix);
                        break;
                    case "color":
                        parseColorTag(parser, namePrefix);
                        break;
                    default:
                        XmlUtils.skip(parser);
                }
            }

        } catch (XmlPullParserException | IOException | ConvertException e) {
            if (parser != null) {
                XmlUtils.skip(parser);
            }
        }
    }

    private void parseStyleTag(XmlPullParser parser, String namePrefix) throws IOException, XmlPullParserException {
        Pair<String, Style> pair = ResourceStyleParser.parseStyleTag(parser);
        mStyles.put(namePrefix + pair.first, pair.second);
    }

    private void parseStringTag(XmlPullParser parser, String namePrefix) throws IOException, XmlPullParserException {
        Pair<String, Value> pair = ResourceStringParser.parseStringXmlInternal(parser);
        mStrings.put(namePrefix + pair.first, pair.second);
    }

    private void parseColorTag(XmlPullParser parser, String namePrefix) throws IOException, XmlPullParserException {
        Pair<String, Value> pair = ResourceColorParser.parseColor(parser);
        mColors.put(namePrefix + pair.first, pair.second);
    }

    private void parseItemTag(XmlPullParser parser, String namePrefix) throws IOException, XmlPullParserException {
        Pair<String, Value> pair = ResourceStringParser.parseItemString(parser);
        if (pair != null) {
            mStrings.put(namePrefix + pair.first, pair.second);
        }
    }
}
