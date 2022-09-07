package com.tyron.builder.gradle.internal.cxx.json;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.File;
import java.io.IOException;

/**
 * GSon TypeAdapter that will convert between File and String.
 */
public class PlainFileGsonTypeAdaptor extends TypeAdapter<File> {
    @Override
    public void write(JsonWriter jsonWriter, File file) throws IOException {
        if (file == null) {
            jsonWriter.nullValue();
            return;
        }
        jsonWriter.value(file.getPath());
    }

    @Override
    public File read(JsonReader jsonReader) throws IOException {
        String path = jsonReader.nextString();
        return new File(path);
    }
}
