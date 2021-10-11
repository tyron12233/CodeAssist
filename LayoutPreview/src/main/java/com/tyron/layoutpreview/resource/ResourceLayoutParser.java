package com.tyron.layoutpreview.resource;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
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

public class ResourceLayoutParser {


    private final ProteusContext mContext;
    private final File mResourceDirectory;

    private final Map<String, Layout> layoutMap = new HashMap<>();

    public ResourceLayoutParser(ProteusContext context, File dir) {
        mContext = context;
        mResourceDirectory = dir;

        layoutMap.putAll(getDefaultLayouts());
    }

    public Map<String, Layout> getLayouts() {
        return layoutMap;
    }

    private Map<String, Layout> getDefaultLayouts() {
        File defaultValues = new File(mResourceDirectory, "layout");
        File[] xmlFiles = defaultValues.listFiles(c -> c.getName().endsWith(".xml"));
        if (xmlFiles == null) {
            return Collections.emptyMap();
        }

        Map<String, Layout> map = new HashMap<>();

        for (File file : xmlFiles) {
            try {
                JsonObject jsonObject = new XmlToJsonConverter()
                        .convert(file);
                Value layout = new ProteusTypeAdapterFactory(mContext)
                        .VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(jsonObject.toString())), false);
                if (layout.isLayout()) {
                    map.put(getName(file), layout.getAsLayout());
                }
            } catch (IOException | XmlPullParserException | ConvertException ignore) {
            }
        }

        return map;
    }

    private String getName(File file) {
        return file.getName().substring(0, file.getName().lastIndexOf("."));
    }
}
