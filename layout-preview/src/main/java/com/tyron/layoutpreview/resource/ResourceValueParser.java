package com.tyron.layoutpreview.resource;

import android.content.res.ColorStateList;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ColorManager;
import com.flipkart.android.proteus.DimensionManager;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.StringManager;
import com.flipkart.android.proteus.StyleManager;
import com.flipkart.android.proteus.parser.ParseHelper;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.Color;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;
import com.tyron.layoutpreview.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ResourceValueParser {

    private static final Set<String> sSupportedDirs = new HashSet<>();

    static {
        sSupportedDirs.add("layout");
        sSupportedDirs.add("color");
        sSupportedDirs.add("drawable");
        sSupportedDirs.add("values");
        sSupportedDirs.add("values-night");
        sSupportedDirs.add("values-night-v8");
    }

    public Map<String, Value> mStrings = new HashMap<>();
    public Map<String, Style> mStyles = new HashMap<>();
    public Map<String, Value> mColors = new HashMap<>();
    private final Map<String, Value> mDimensions = new HashMap<>();

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
    private final DimensionManager mDimensionManager = new DimensionManager() {
        @NonNull
        @Override
        protected Map<String, Value> getDimensions() {
            return mDimensions;
        }
    };

    private ProteusContext mContext;

    public void setProteusContext(ProteusContext context) {
        mContext = context;
    }

    public StringManager getStringManager() {
        return mStringManager;
    }

    public StyleManager getStyleManager() {
        return mStyleManager;
    }

    public ColorManager getColorManager() {
        return mColorManager;
    }

    public DimensionManager getDimensionManager() {
        return mDimensionManager;
    }

    public void parse(AndroidModule module) {
        File resourcesDir = module.getAndroidResourcesDirectory();
        parseResDirectory(resourcesDir, "");

        for (File library : module.getLibraries()) {
            File parent = library.getParentFile();
            if (parent == null) {
                continue;
            }
            parseResDirectory(new File(parent, "res"), "");
        }
    }

    private void parseResDirectory(File resDir, String prefix) {
        if (resDir == null || !resDir.exists()) {
            return;
        }

        File[] files = resDir.listFiles(File::isDirectory);
        if (files != null) {
            for (File file : files) {
                if (!sSupportedDirs.contains(file.getName())) {
                    continue;
                }

                File[] children = file.listFiles(c -> c.getName().endsWith(".xml"));
                if (children != null) {
                    if ("color".equals(file.getName())) {
                        parseColor(children, prefix);
                    } else {
                        parse(children, prefix);
                    }
                }
            }
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
        XmlPullParser parser;

        parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(reader);

        XmlUtils.advanceToRootNode(parser);

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            try {
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
                    case "dimen":
                        parseDimension(parser, namePrefix);
                        break;
                    default:
                        XmlUtils.skip(parser);
                }

            } catch (XmlPullParserException | IOException e) {
                System.out.println(e);
            }
        }
    }

    private void parseColor(File[] children, String namePrefix) {
        for (File child : children) {
            try {
                XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(new InputStreamReader(new FileInputStream(child)));
                XmlUtils.advanceToRootNode(parser);

                parseColor(parser, child.getName(), namePrefix);
            } catch (XmlPullParserException | IOException e) {
                // ignored
            }
        }
    }
    private void parseColor(XmlPullParser parser, String fileName, String namePrefix) throws IOException,
            XmlPullParserException {
        XmlToJsonConverter converter = new XmlToJsonConverter();
        try {
            JsonObject jsonObjects = converter.convert(parser);
            Value read =
                    new ProteusTypeAdapterFactory(mContext).VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(jsonObjects.toString())), true);
            if (read.isObject()) {
                ObjectValue objectValue = read.getAsObject();
                Array children = objectValue.getAsArray("children");
                int[][] states = new int[children.size()][];
                Value[] colors = new Value[children.size()];
                float[] alphas = new float[children.size()];
                for (int i = 0; i < children.size(); i++) {
                    // set the alpha to be 1 by default
                    alphas[i] = 1;

                    ObjectValue child = children.get(i).getAsObject();
                    List<Integer> childStates = new ArrayList<>();
                    for (Map.Entry<String, Value> entry : child.entrySet()) {
                        Integer stateInteger =
                                DrawableValue.StateListValue.sStateMap.get(entry.getKey());
                        if (stateInteger != null) {
                            int result = ParseHelper.parseBoolean(entry.getValue()) ? stateInteger : -stateInteger;
                            childStates.add(result);
                        }

                        if ("android:color".equals(entry.getKey())) {
                            colors[i] = entry.getValue();
                        } else if ("android:alpha".equals(entry.getKey())) {
                            alphas[i] = ParseHelper.parseFloat(entry.getValue());
                        }
                    }

                    int[] temp = new int[childStates.size()];
                    for (int j = 0; j < childStates.size(); j++) {
                        Integer childState = childStates.get(j);
                        temp[j] = childState;
                    }
                    states[i] = temp;
                }

                Color.LazyStateList lazyStateList = Color.LazyStateList.valueOf(states, colors, alphas);
                mColors.put(fileName.replace(".xml", ""), lazyStateList);
            }
        } catch (ConvertException e) {
            Log.e("ParseSelectorTag", "Unable to convert to json", e);
        }
    }

    private void parseDimension(XmlPullParser parser, String namePrefix) throws IOException,
            XmlPullParserException {
        Pair<String, Value> pair = ResourceDimensionParser.parseDimension(parser);
        mDimensions.put(namePrefix + pair.first, pair.second);
    }

    private void parseStyleTag(XmlPullParser parser, String namePrefix) throws IOException,
            XmlPullParserException {
        Pair<String, Style> pair = ResourceStyleParser.parseStyleTag(parser);
        if (!mStyles.containsKey(pair.first)) {
            mStyles.put(namePrefix + pair.first, pair.second);
        }
    }

    private void parseStringTag(XmlPullParser parser, String namePrefix) throws IOException,
            XmlPullParserException {
        Pair<String, Value> pair = ResourceStringParser.parseStringXmlInternal(parser);
        if (!mStrings.containsKey(pair.first)) {
            mStrings.put(namePrefix + pair.first, pair.second);
        }
    }

    private void parseColorTag(XmlPullParser parser, String namePrefix) throws IOException,
            XmlPullParserException {
        Pair<String, Value> pair = ResourceColorParser.parseColor(parser);
        if (!mColors.containsKey(pair.first)) {
            mColors.put(namePrefix + pair.first, pair.second);
        }
    }

    private void parseItemTag(XmlPullParser parser, String namePrefix) throws IOException,
            XmlPullParserException {
        Pair<String, Value> pair = ResourceStringParser.parseItemString(parser);
        if (pair != null) {
            mStrings.put(namePrefix + pair.first, pair.second);
        }
    }
}
