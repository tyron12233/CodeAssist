package com.tyron.builder.gradle.internal.tasks.featuresplit;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.sdklib.AndroidVersion;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Container for all the feature split metadata. */
public class FeatureSetMetadata {

    public static final Integer MAX_NUMBER_OF_SPLITS_BEFORE_O = 50;
    public static final Integer MAX_NUMBER_OF_SPLITS_STARTING_IN_O = 127;

    @VisibleForTesting static final String OUTPUT_FILE_NAME = "feature-metadata.json";
    /** Base module or application module resource ID */
    @VisibleForTesting public static final int BASE_ID = 0x7F;

    private final File sourceFile;
    private final Set<FeatureInfo> featureSplits;
    private final Integer maxNumberOfSplitsBeforeO;

    public FeatureSetMetadata(Integer maxNumberOfSplitsBeforeO) {
        this.maxNumberOfSplitsBeforeO = maxNumberOfSplitsBeforeO;
        featureSplits = new HashSet<>();
        sourceFile = null;
    }

    private FeatureSetMetadata(@NonNull Set<FeatureInfo> featureSplits, @NonNull File sourceFile) {
        this.maxNumberOfSplitsBeforeO =
                Integer.max(MAX_NUMBER_OF_SPLITS_BEFORE_O, featureSplits.size());
        this.featureSplits = ImmutableSet.copyOf(featureSplits);
        this.sourceFile = sourceFile;
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public void addFeatureSplit(
            int minSdkVersion,
            @NonNull String modulePath,
            @NonNull String featureName,
            @NonNull String packageName) {

        int id;
        if (minSdkVersion < AndroidVersion.VersionCodes.O) {
            if (featureSplits.size() >= maxNumberOfSplitsBeforeO) {
                throw new RuntimeException(
                        "You have reached the maximum number of feature splits : "
                                + maxNumberOfSplitsBeforeO);
            }
            // allocate split ID backwards excluding BASE_ID.
            id = BASE_ID - 1 - featureSplits.size();
        } else {
            if (featureSplits.size() >= MAX_NUMBER_OF_SPLITS_STARTING_IN_O) {
                throw new RuntimeException(
                        "You have reached the maximum number of feature splits : "
                                + MAX_NUMBER_OF_SPLITS_STARTING_IN_O);
            }
            // allocated forward excluding BASE_ID
            id = BASE_ID + 1 + featureSplits.size();
        }

        featureSplits.add(new FeatureInfo(modulePath, featureName, id, packageName));
    }

    @Nullable
    public Integer getResOffsetFor(@NonNull String modulePath) {
        Optional<FeatureInfo> featureInfo =
                featureSplits
                        .stream()
                        .filter(metadata -> metadata.modulePath.equals(modulePath))
                        .findFirst();
        return featureInfo.isPresent() ? featureInfo.get().resOffset : null;
    }

    @Nullable
    public String getFeatureNameFor(@NonNull String modulePath) {
        Optional<FeatureInfo> featureInfo =
                featureSplits
                        .stream()
                        .filter(metadata -> metadata.modulePath.equals(modulePath))
                        .findFirst();
        return featureInfo.isPresent() ? featureInfo.get().featureName : null;
    }

    @NonNull
    public Map<String, String> getFeatureNameToNamespaceMap() {
        return featureSplits.stream()
                .collect(toImmutableMap(info -> info.featureName, info -> info.namespace));
    }

    public void save(@NonNull File outputFile) throws IOException {
        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.create();
        Files.asCharSink(outputFile, Charsets.UTF_8).write(gson.toJson(featureSplits));
    }

    /**
     * Loads the feature set metadata file
     *
     * @param input the location of the file, or the folder that contains it.
     * @return the FeatureSetMetadata instance that contains all the data from the file
     * @throws IOException if the loading failed.
     */
    @NonNull
    public static FeatureSetMetadata load(@NonNull File input) throws IOException {
        if (input.isDirectory()) {
            input = new File(input, OUTPUT_FILE_NAME);
        }

        GsonBuilder gsonBuilder = new GsonBuilder();
        Gson gson = gsonBuilder.create();
        Type typeToken = new TypeToken<HashSet<FeatureInfo>>() {}.getType();
        try (FileReader fileReader = new FileReader(input)) {
            Set<FeatureInfo> featureIds = gson.fromJson(fileReader, typeToken);
            return new FeatureSetMetadata(featureIds, input);
        }
    }

    private static class FeatureInfo {
        final String modulePath;
        final String featureName;
        final int resOffset;
        final String namespace;

        FeatureInfo(String modulePath, String featureName, int resOffset, String namespace) {
            this.modulePath = modulePath;
            this.featureName = featureName;
            this.resOffset = resOffset;
            this.namespace = namespace;
        }
    }
}
