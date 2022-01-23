package com.tyron.layoutpreview.resource;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Value;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.tyron.builder.project.api.FileManager;
import com.tyron.layoutpreview.convert.ConvertException;
import com.tyron.layoutpreview.convert.XmlToJsonConverter;
import com.tyron.layoutpreview.convert.adapter.ProteusTypeAdapterFactory;
import com.tyron.vectorparser.VectorValue;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ResourceDrawableParser {

    private final ProteusContext mContext;
    private final File mResourceDirectory;
    private final FileManager mFileManager;
    private final Map<String, DrawableValue> drawableValueMap = new HashMap<>();

    public ResourceDrawableParser(ProteusContext context, File dir, FileManager fileManager) {
        mContext = context;
        mResourceDirectory = dir;
        mFileManager = fileManager;
        drawableValueMap.putAll(getDefaultDrawables());
    }

    public Map<String, DrawableValue> getDefaultDrawables() {
        Map<String, DrawableValue> map = new HashMap<>();
        File defaultValues = new File(mResourceDirectory, "drawable");
        File[] xmlFiles = defaultValues.listFiles();
        if (xmlFiles == null) {
            return map;
        }

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
        try {
            return DrawableValue.valueOf(file);
        } catch (Throwable e) {
            // Do not propagate exceptions just do not include the drawable
            return null;
        }
    }

    @Nullable
    private DrawableValue parseXml(File file) throws IOException, ConvertException, XmlPullParserException {
        Optional<CharSequence> contents = mFileManager.getFileContent(file);
        if (contents.isPresent()) {
            String contentsString = contents.get().toString();
            JsonObject converted = new XmlToJsonConverter()
                    .convert(contentsString);
            Value value = new ProteusTypeAdapterFactory(mContext)
                    .VALUE_TYPE_ADAPTER.read(new JsonReader(new StringReader(converted.toString())), true);
            ObjectValue objectValue = value.getAsObject();
            if (objectValue != null) {
                if ("vector".equals(objectValue.getAsString("type"))) {
                    return new VectorValue(contentsString);
                }
                try {
                    return DrawableValue.valueOf(objectValue, mContext);
                } catch (Throwable e) {
                    // TODO LOG
                }
            }
        }
        return null;
    }

    private boolean isImageFile(File file) {
        String n = file.getName();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    private String getName(File file) {
        return file.getName().substring(0, file.getName().lastIndexOf("."));
    }
}
