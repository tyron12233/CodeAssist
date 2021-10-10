package com.tyron.layoutpreview.resource;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ResourceDrawableParser {

    private final ProteusContext mContext;
    private final File mResourceDirectory;

    private final Map<String, DrawableValue> drawableValueMap = new HashMap<>();

    public ResourceDrawableParser(ProteusContext context, File dir) {
        mContext = context;
        mResourceDirectory = dir;

        drawableValueMap.putAll(getDefaultDrawables());
    }

    public Map<String, DrawableValue> getDefaultDrawables() {
        File defaultValues = new File(mResourceDirectory, "drawable");
        File[] xmlFiles = defaultValues.listFiles();
        if (xmlFiles == null) {
            return Collections.emptyMap();
        }

        Map<String, DrawableValue> map = new HashMap<>();
        for (File file : xmlFiles) {
            DrawableValue value = null;

            if (isImageFile(file)) {
                value = parseFile(file);
            } else if (file.getName().endsWith(".xml")) {
                try {
                    value = parseXml(file);
                    if (value != null) {
                        map.put(getName(file), value);
                    }
                } catch (IOException | ConvertException | XmlPullParserException ignore) {

                }
            }

            if (value != null) {
                map.put(getName(file), value);
            }
        }

        return map;
    }

    private DrawableValue parseFile(File file) {
        return DrawableValue.valueOf(file);
    }

    private DrawableValue parseXml(File file) throws IOException, ConvertException, XmlPullParserException {
        JsonObject converted = new XmlToJsonConverter()
                .convert(file);
        Value value = new ProteusTypeAdapterFactory(mContext)
                .VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(converted.toString())), true);
        ObjectValue objectValue = value.getAsObject();
        return DrawableValue.valueOf(objectValue, mContext);
    }

    private boolean isImageFile(File file) {
        String n = file.getName();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    private String getName(File file) {
        return file.getName().substring(0, file.getName().lastIndexOf("."));
    }
}
