package com.tyron.builder.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.android.annotations.NonNull;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Processes a template to generate a file somewhere.
 */
class TemplateProcessor {

    private final InputStream mTemplateStream;
    private final Map<String, String> mPlaceHolderMap;

    /**
     * Creates a processor
     * @param templateStream the stream to read the template file from
     * @param placeHolderMap
     */
    public TemplateProcessor(@NonNull InputStream templateStream,
                             @NonNull Map<String, String> placeHolderMap) {
        mTemplateStream = checkNotNull(templateStream);
        mPlaceHolderMap = checkNotNull(placeHolderMap);
    }

    /**
     * Generates the file from the template.
     * @param outputFile the file to create
     */
    public void generate(File outputFile) throws IOException {
        String template = readEmbeddedTextFile(mTemplateStream);

        String content = replaceParameters(template, mPlaceHolderMap);

        writeFile(outputFile, content);
    }

    /**
     * Reads and returns the content of a text file embedded in the jar file.
     * @param templateStream the stream to read the template file from
     * @return null if the file could not be read
     * @throws java.io.IOException
     */
    private String readEmbeddedTextFile(InputStream templateStream) throws IOException {
        InputStreamReader reader = new InputStreamReader(templateStream, Charsets.UTF_8);

        try {
            return CharStreams.toString(reader);
        } finally {
            reader.close();
        }
    }

    private void writeFile(File file, String content) throws IOException {
        Files.asCharSink(file, Charsets.UTF_8).write(content);
    }

    /**
     * Replaces placeholders found in a string with values.
     *
     * @param str the string to search for placeholders.
     * @param parameters a map of <placeholder, Value> to search for in the string
     * @return A new String object with the placeholder replaced by the values.
     */
    private String replaceParameters(String str, Map<String, String> parameters) {

        for (Entry<String, String> entry : parameters.entrySet()) {
            String value = entry.getValue();
            if (value != null) {
                str = str.replaceAll(entry.getKey(), value);
            }
        }

        return str;
    }
}
