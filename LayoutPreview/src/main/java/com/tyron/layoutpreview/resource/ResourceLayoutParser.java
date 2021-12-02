package com.tyron.layoutpreview.resource;

import android.util.Log;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.builder.project.api.FileManager;
import com.tyron.layoutpreview.BuildConfig;
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
import java.util.Optional;

public class ResourceLayoutParser {
    private static final String TAG = ResourceLayoutParser.class.getSimpleName();

    private final ProteusContext mContext;
    private final File mResourceDirectory;
    private final FileManager mFileManager;

    private final Map<String, Layout> layoutMap = new HashMap<>();

    public ResourceLayoutParser(ProteusContext context, File dir, FileManager fileManager) {
        mContext = context;
        mResourceDirectory = dir;
        mFileManager = fileManager;

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
                Value layout = parseLayout(file);
                if (layout != null && layout.isLayout()) {
                    map.put(getName(file), layout.getAsLayout());
                }
            } catch (IOException | XmlPullParserException | ConvertException e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Unable to parse file: " + file.getName(), e);
                }
            }
        }

        return map;
    }

    @Nullable
    private Value parseLayout(File file) throws ConvertException, XmlPullParserException, IOException {
        Optional<CharSequence> fileContent = mFileManager.getFileContent(file);
        if (fileContent.isPresent()) {
            String contents = fileContent.get().toString();
            JsonObject jsonObject = new XmlToJsonConverter()
                    .convert(contents);
            return new ProteusTypeAdapterFactory(mContext).VALUE_TYPE_ADAPTER
                    .read(new JsonReader(new StringReader(jsonObject.toString())), false);
        }
        return null;
    }

    private String getName(File file) {
        return file.getName().substring(0, file.getName().lastIndexOf("."));
    }
}
