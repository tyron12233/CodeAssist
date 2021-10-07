package com.tyron.layoutpreview.manager;

import com.flipkart.android.proteus.DrawableManager;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;

import org.apache.commons.io.FileUtils;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class ResourceDrawableManager extends DrawableManager {

    private final ProteusContext mContext;
    private final File mResourcesDirectory;

    public ResourceDrawableManager(ProteusContext context, File resourcesDirectory) {
        mContext = context;
        mResourcesDirectory = resourcesDirectory;
    }

    private void getDefaultDrawables() {
        File drawableDirectory = new File(mResourcesDirectory, "drawable");
        File[] xmlFiles = drawableDirectory.listFiles(c -> c.getName().endsWith(".xml") || c.getName().endsWith(".png"));
        Map<String, DrawableValue> drawables = new HashMap<>();

        if (xmlFiles != null) {
            for (File xmlFile : xmlFiles) {
                try {
                    String xmlContents = FileUtils.readFileToString(xmlFile, Charset.defaultCharset());
                    JsonObject json = new XmlToJsonConverter().convert(xmlContents);

                    Value value = new ProteusTypeAdapterFactory(mContext).VALUE_TYPE_ADAPTER.fromJson(json.toString());

                } catch (IOException | XmlPullParserException | ConvertException ignore) {

                }
            }
        }
    }

    @Override
    protected Map<String, DrawableValue> getDrawables() {
        return null;
    }
}
