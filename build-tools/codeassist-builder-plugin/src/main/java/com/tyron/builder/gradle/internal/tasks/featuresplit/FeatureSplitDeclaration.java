package com.tyron.builder.gradle.internal.tasks.featuresplit;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.gradle.api.file.FileCollection;

/**
 * Information containing a feature split declaration that can be consumed by other modules as
 * persisted json file
 */
public class FeatureSplitDeclaration {

    @VisibleForTesting static final String PERSISTED_FILE_NAME = "feature-split.json";

    @NonNull private final String modulePath;
    @NonNull private final String namespace;

    public FeatureSplitDeclaration(@NonNull String modulePath, @NonNull String namespace) {
        this.modulePath = modulePath;
        this.namespace = namespace;
    }

    @NonNull
    public String getModulePath() {
        return modulePath;
    }

    @NonNull
    public String getNamespace() {
        return namespace;
    }

    public void save(@NonNull File outputDirectory) throws IOException {
        File outputFile = new File(outputDirectory, PERSISTED_FILE_NAME);
        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.create();
        FileUtils.write(outputFile, gson.toJson(this));
    }

    @NonNull
    public static FeatureSplitDeclaration load(@NonNull FileCollection input) throws IOException {
        File persistedFile = getOutputFile(input);
        if (persistedFile == null) {
            throw new FileNotFoundException("No feature split declaration present");
        }
        return load(persistedFile);
    }

    @NonNull
    public static FeatureSplitDeclaration load(@NonNull File input) throws IOException {
        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.create();
        try (FileReader fileReader = new FileReader(input)) {
            return gson.fromJson(fileReader, FeatureSplitDeclaration.class);
        }
    }

    @Nullable
    private static File getOutputFile(@NonNull FileCollection input) {
        for (File file : input.getAsFileTree().getFiles()) {
            if (file.getName().equals(PERSISTED_FILE_NAME)) {
                return file;
            }
        }
        return null;
    }

    @NonNull
    public static File getOutputFile(@NonNull File directory) {
        return new File(directory, PERSISTED_FILE_NAME);
    }
}
