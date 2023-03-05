package com.tyron.builder.ide.common.blame;

import static com.android.SdkConstants.DOT_JSON;

import com.android.annotations.NonNull;
import com.android.ide.common.blame.SourceFile;
import com.android.ide.common.blame.SourceFilePosition;
import com.android.ide.common.blame.SourcePosition;
import com.android.utils.Pair;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Utility functions to save and load individual merge log files.
 *
 * They are sharded by the directory containing the file, giving a file layout of the form:
 *
 * .
 * ├── multi
 * │   └── values.json
 * └── single
 *     ├── drawable.json
 *     ├── layout.json
 *     └── layout-land.json
 *
 * This allows incremental changes to only have to load and rewrite a small fraction of the
 * total log data.
 */
public class MergingLogPersistUtil {

    private static final SourceFileJsonTypeAdapter mSourceFileJsonTypeAdapter
            = new SourceFileJsonTypeAdapter();

    private static final SourcePositionJsonTypeAdapter mSourcePositionJsonTypeAdapter
            = new SourcePositionJsonTypeAdapter();

    private static final SourceFilePositionJsonSerializer mSourceFilePositionJsonTypeAdapter
            = new SourceFilePositionJsonSerializer();

    private static final SourcePositionsSerializer.JsonTypeAdapter mSourcePositionsJsonTypeAdapter =
            new SourcePositionsSerializer.JsonTypeAdapter();

    private static final String KEY_OUTPUT_FILE = "outputFile";
    private static final String KEY_FROM = "from";
    private static final String KEY_TO = "to";
    private static final String KEY_MERGED = "merged";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_MAP = "map";
    private static final String KEY_LOGS = "logs";
    private static final String INDENT_STRING = "    ";

    private static final String START_LINES = "startLines";
    private static final String START_COLUMNS = "startColumns";
    private static final String START_OFFSETS = "startOffsets";
    private static final String END_LINES = "endLines";
    private static final String END_COLUMNS = "endColumns";
    private static final String END_OFFSETS = "endOffsets";

    private static File getMultiFile(File folder, String shard) {
        // Previously, these log files used to be kept under the "multi" directory, and we did not
        // clean it at the start of a full build. Recently, we introduced a new format for these log
        // files. Therefore, if the users switch between versions of the plugin that have different
        // formats of the log files, the next build will fail to read the existing log files. (We
        // tried to support a newer plugin version reading the old format, but we still cannot
        // support an older plugin version reading the new format.) To work around this issue, we
        // need to use a different directory to contain log files with the new format.
        // Note that, with a recent change, we now also clean these log files at the start of a full
        // build. Therefore, moving forward, we won't need to use a new directory again even if we
        // introduce yet another new format.
        return new File(new File(folder, "multi-v2"), shard + DOT_JSON);
    }

    private static File getSingleFile(File folder, String shard) {
        return new File (new File(folder, "single"), shard + DOT_JSON);
    }

    /**
     * Version 2 format of the merger-log files. The lines, columns et offsets are now collections
     * of SourcePositions for the associated source. The end lines, columns and offsets are not
     * repeated if they are the same values as the start equivalent.
     *
     * <pre>
     *     {
     *   "logs": [
     *   {
     *       "outputFile": ".../merged/values/values.xml",
     *           "map": [
     *       {
     *           "source": ".../exploded/b/values/values.xml",
     *               "from": {
     *                   "startLines": "2,3,4",
     *                   "startColumns": "3,3,3",
     *                   "startOffsets": "14,26,34"
     *       },
     *           "to": {
     *                   "startLines": "4,8,9",
     *                   "startColumns": "1,1,1",
     *                   "startOffsets": "34,23,45",
     *                   "endLines": "6,9,11",
     *                   "endColumns": "20,34,54",
     *                   "endOffsets": "100,165,234"
     *       }
     *       },
     *       {
     *           "source": "/.../exploded/a/values/values.xml",
     *                "from": {
     *                   "startLines": "7",
     *                   "startColumns": "8",
     *                   "startOffsets": "20"
     *       },
     *           "to": {
     *           "startLines": "1",
     *                   "startColumns": "2",
     *                   "startOffsets": "3",
     *                   "endLines": "7",
     *                   "endColumns": "1",
     *                   "endOffsets": "120"
     *       }
     *       }
     *       ]
     *   }
     * ]
     * }
     * </pre>
     *
     * the "to" is also omitted if all the values are the same as the "from".
     *
     * <pre>
     * {
     * "logs": [
     *   {
     *       "outputFile": ".../incremental/mergeDebugResources/merged.dir/values-fr/values-fr.xml",
     *       "map": [
     *           {
     *               "source": ".../appcompat-v7-23.4.0.aar/res/values-fr/values-fr.xml",
     *               "from": {
     *                   "startLines": "2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21",
     *                   "startColumns": "4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4",
     *                   "startOffsets": "55,166,274,397,512,623,705,811,941,1024,1104,1190,1300,1412,1515,1626,1733,1840,1962,2061",
     *                   "endColumns": "110,107,122,114,110,81,105,129,82,79,85,109,111,102,110,106,106,121,98,100",
     *                   "endOffsets": "161,269,392,507,618,700,806,936,1019,1099,1185,1295,1407,1510,1621,1728,1835,1957,2056,2157"
     *               }
     *           }
     *       ]
     *   }
     * ]
     * }
     * </pre>
     *
     * @param folder the folder in which the merged logs are located.
     * @param shard the merged log
     * @param map the merged actions mappings.
     * @throws IOException
     */
    static void saveToMultiFileVersion2(
            @NonNull File folder,
            @NonNull String shard,
            @NonNull Map<SourceFile, Map<SourcePosition, SourceFilePosition>> map)
            throws IOException {
        File file = getMultiFile(folder, shard);
        file.getParentFile().mkdir();
        try (JsonWriter out = new JsonWriter(Files.newWriter(file, Charsets.UTF_8))) {
            out.setIndent(INDENT_STRING);
            out.beginObject().name(KEY_LOGS);
            out.beginArray();
            for (Map.Entry<SourceFile, Map<SourcePosition, SourceFilePosition>> entry :
                    map.entrySet()) {
                out.beginObject().name(KEY_OUTPUT_FILE);
                mSourceFileJsonTypeAdapter.write(out, entry.getKey());
                out.name(KEY_MAP);
                out.beginArray();
                // key is "to" and value is "from"
                Map<SourcePosition, SourceFilePosition> mappings = entry.getValue();
                Map<SourceFile, Pair<SourcePositionsSerializer, SourcePositionsSerializer>>
                        sourceFileListMap = normalize(mappings);

                for (Map.Entry<
                                SourceFile,
                                Pair<SourcePositionsSerializer, SourcePositionsSerializer>>
                        sourceFileListEntry : sourceFileListMap.entrySet()) {

                    out.beginObject();
                    out.name(KEY_SOURCE).value(sourceFileListEntry.getKey().getSourcePath());

                    Pair<SourcePositionsSerializer, SourcePositionsSerializer> serializerPair =
                            sourceFileListEntry.getValue();

                    mSourcePositionsJsonTypeAdapter.write(out, serializerPair.getFirst());
                    if (!serializerPair.getFirst().equals(serializerPair.getSecond())) {
                        mSourcePositionsJsonTypeAdapter.write(out, serializerPair.getSecond());
                    }

                    out.endObject();
                }

                out.endArray();
                out.endObject();
            }
            out.endArray();
            out.endObject();
        }
    }

    /**
     * Utility class to serialize a number of merged actions for a single source file to a single
     * output file mapping.
     */
    protected static final class SourcePositionsSerializer {

        enum Kind {
            FROM,
            TO
        }

        private final Kind kind;

        SourcePositionsSerializer(Kind kind) {
            this.kind = kind;
        }

        private final StringBuilder startLines = new StringBuilder();
        private final StringBuilder startColumns = new StringBuilder();
        private final StringBuilder startOffsets = new StringBuilder();
        private final StringBuilder endLines = new StringBuilder();
        private final StringBuilder endColumns = new StringBuilder();
        private final StringBuilder endOffsets = new StringBuilder();

        void append(SourcePosition sourcePosition) {
            append(startLines, sourcePosition.getStartLine());
            append(startColumns, sourcePosition.getStartColumn());
            append(startOffsets, sourcePosition.getStartOffset());
            append(endLines, sourcePosition.getEndLine());
            append(endColumns, sourcePosition.getEndColumn());
            append(endOffsets, sourcePosition.getEndOffset());
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    startLines, startColumns, startOffsets, endLines, endColumns, endOffsets);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SourcePositionsSerializer other = (SourcePositionsSerializer) o;
            return startLines.toString().equals(other.startLines.toString())
                    && startColumns.toString().equals(other.startColumns.toString())
                    && startOffsets.toString().equals(other.startOffsets.toString())
                    && endLines.toString().equals(other.endLines.toString())
                    && endColumns.toString().equals(other.endColumns.toString())
                    && endOffsets.toString().equals(other.endOffsets.toString());
        }

        private static void append(StringBuilder stringBuilder, int value) {
            if (stringBuilder.length() > 0) {
                stringBuilder.append(',');
            }
            stringBuilder.append(value);
        }

        private static final class JsonTypeAdapter extends TypeAdapter<SourcePositionsSerializer> {

            @Override
            public void write(JsonWriter writer, SourcePositionsSerializer value)
                    throws IOException {
                writer.name(value.kind.name().toLowerCase(Locale.US));
                writer.beginObject();
                String persistedStartLines = value.startLines.toString();
                writer.name(START_LINES).value(persistedStartLines);
                String persistedStartColumns = value.startColumns.toString();
                writer.name(START_COLUMNS).value(persistedStartColumns);
                String persistedStartOffsets = value.startOffsets.toString();
                writer.name(START_OFFSETS).value(persistedStartOffsets);
                String persisteEndLines = value.endLines.toString();
                if (!persisteEndLines.equals(persistedStartLines)) {
                    writer.name(END_LINES).value(persisteEndLines);
                }
                String persistedEndColums = value.endColumns.toString();
                if (!persistedEndColums.equals(persistedStartColumns)) {
                    writer.name(END_COLUMNS).value(value.endColumns.toString());
                }
                String persistedEndOffsets = value.endOffsets.toString();
                if (!persistedEndOffsets.equals(persistedStartOffsets)) {
                    writer.name(END_OFFSETS).value(value.endOffsets.toString());
                }
                writer.endObject();
            }

            @Override
            public SourcePositionsSerializer read(JsonReader in) throws IOException {
                throw new IOException(
                        "SourcePositionsSerializer is not meant to be read from Json");
            }
        }
    }

    /**
     * flatten the merged records per source and output file so it can be persisted as a list of
     * lines, columns and offsets collections.
     *
     * @return a pair of serializers for the from and to records organized by files.
     */
    private static Map<SourceFile, Pair<SourcePositionsSerializer, SourcePositionsSerializer>>
            normalize(Map<SourcePosition, SourceFilePosition> mappings) {
        Map<SourceFile, Pair<SourcePositionsSerializer, SourcePositionsSerializer>>
                perSourcePositions = new HashMap<>();
        mappings.entrySet()
                .forEach(
                        entry -> {
                            SourceFile sourceFile = entry.getValue().getFile();
                            if (!perSourcePositions.containsKey(sourceFile)) {
                                perSourcePositions.put(
                                        sourceFile,
                                        Pair.of(
                                                new SourcePositionsSerializer(
                                                        SourcePositionsSerializer.Kind.FROM),
                                                new SourcePositionsSerializer(
                                                        SourcePositionsSerializer.Kind.TO)));
                            }
                            Pair<SourcePositionsSerializer, SourcePositionsSerializer> serializers =
                                    perSourcePositions.get(sourceFile);
                            // Pair.first is "from" and Pair.second is "to"
                            serializers.getFirst().append(entry.getValue().getPosition());
                            serializers.getSecond().append(entry.getKey());
                        });
        return perSourcePositions;
    }

    /**
     * Loads a version 2 merge logs from disk. Resulting in memory model is exactly the same as
     * loading a version 1 merge logs.
     *
     * @param folder the folder containing merge logs.
     * @param shard the shard to load.
     * @param relativeResFilepathEnabled uses RelativeResourcesUtils for resolving absolute path.
     * @return the memory model of merged logs.
     */
    @NonNull
    static Map<SourceFile, Map<SourcePosition, SourceFilePosition>> loadFromMultiFileVersion2(
            @NonNull File folder, @NonNull String shard, boolean relativeResFilepathEnabled) {

        Map<SourceFile, Map<SourcePosition, SourceFilePosition>> map = Maps.newConcurrentMap();
        JsonReader reader;
        File file = getMultiFile(folder, shard);
        if (!file.exists()) {
            return map;
        }
        try {
            reader = new JsonReader(Files.newReader(file, Charsets.UTF_8));
        } catch (FileNotFoundException e) {
            // Shouldn't happen unless it disappears under us.
            return map;
        }
        try {
            reader.beginObject();
            String name = reader.nextName();
            if (!name.equals("logs")) {
                throw new IOException(String.format("Malformed log file : %s", name));
            }
            reader.beginArray();
            while (reader.peek() != JsonToken.END_ARRAY) {
                reader.beginObject();
                SourceFile toFile = SourceFile.UNKNOWN;
                // key is "to" and value if "from"
                Map<SourcePosition, SourceFilePosition> innerMap = Maps.newLinkedHashMap();
                while (reader.peek() != JsonToken.END_OBJECT) {
                    name = reader.nextName();
                    if (name.equals(KEY_OUTPUT_FILE)) {
                        String pathname = reader.nextString();
                        toFile = new SourceFile(new File(pathname));
                        // When relative resources are used, the key of the merging log will
                        // follow the relative resource set identification format, this sets
                        // the key to the relative path.
                        if (relativeResFilepathEnabled) {
                            toFile.setOverrideSourcePath(pathname);
                        }
                    } else if (name.equals(KEY_MAP)) {
                        reader.beginArray();
                        while (reader.peek() != JsonToken.END_ARRAY) {
                            reader.beginObject();
                            Map<String, List<Integer>> toKeyValuePairs = new HashMap<>();
                            Map<String, List<Integer>> fromKeyValuePairs = new HashMap<>();
                            SourceFile fromFile = SourceFile.UNKNOWN;
                            while (reader.peek() != JsonToken.END_OBJECT) {
                                String propertyName = reader.nextName();
                                if (propertyName.equals(KEY_SOURCE)) {
                                    fromFile = new SourceFile(new File(reader.nextString()));
                                }
                                if (propertyName.equals(KEY_FROM)) {
                                    readCondensedPositions(reader, fromKeyValuePairs);
                                } else if (propertyName.equals(KEY_TO)) {
                                    readCondensedPositions(reader, toKeyValuePairs);
                                }
                            }
                            reader.endObject();

                            // now build the model.
                            for (int i = 0; i < fromKeyValuePairs.get(START_LINES).size(); i++) {
                                SourcePosition fromPosition =
                                        extractSourcePosition(fromKeyValuePairs, i);
                                SourcePosition toPosition =
                                        toKeyValuePairs.get(START_LINES) != null
                                                ? extractSourcePosition(toKeyValuePairs, i)
                                                : fromPosition;
                                innerMap.put(
                                        toPosition, new SourceFilePosition(fromFile, fromPosition));
                            }
                        }
                        reader.endArray();
                    } else {
                        throw new IOException(String.format("Unexpected properties %s", name));
                    }
                }
                reader.endObject();
                map.put(toFile, innerMap);
            }
            reader.endArray();
            reader.endObject();
            return map;
        } catch (IOException e) {
            // TODO: trigger a non-incremental merge if this happens.
            throw new RuntimeException(e);
        } finally {
            try {
                reader.close();
            } catch (Throwable e2) {
                // well, we tried.
            }
        }
    }

    /**
     * read all collections of lines, columns and offsets from a {@link JsonReader} and store then
     * in a map using the attribute name as the key.
     *
     * @param reader the json reader to read from.
     * @param positions the positions to save the collections in.
     * @throws IOException if reading failed.
     */
    private static void readCondensedPositions(
            JsonReader reader, Map<String, List<Integer>> positions) throws IOException {

        reader.beginObject();
        while (reader.peek() != JsonToken.END_OBJECT) {
            String attrNames = reader.nextName();
            List<Integer> numbers =
                    StreamSupport.stream(
                                    Splitter.on(',').split(reader.nextString()).spliterator(),
                                    false)
                            .map(Integer::valueOf)
                            .collect(Collectors.toList());

            positions.put(attrNames, numbers);
        }
        reader.endObject();
    }

    /**
     * Creates a {@link SourcePosition} instance from the collections of persisted lines, columns
     * and offsets using the same index in each collection.
     */
    private static SourcePosition extractSourcePosition(
            Map<String, List<Integer>> positions, int index) {
        return new SourcePosition(
                positions.get(START_LINES).get(index),
                positions.get(START_COLUMNS).get(index),
                positions.get(START_OFFSETS).get(index),
                positions.containsKey(END_LINES)
                        ? positions.get(END_LINES).get(index)
                        : positions.get(START_LINES).get(index),
                positions.containsKey(END_COLUMNS)
                        ? positions.get(END_COLUMNS).get(index)
                        : positions.get(START_COLUMNS).get(index),
                positions.containsKey(END_OFFSETS)
                        ? positions.get(END_OFFSETS).get(index)
                        : positions.get(START_OFFSETS).get(index));
    }

    /**
     * File format for single file blame.
     * <pre>[
     *     {
     *         "merged": "/path/build/intermediates/res/merged/f1/debug/layout/main.xml",
     *         "source": "/path/src/main/res/layout/main.xml"
     *     },
     *     ...
     * ]</pre>
     * @param folder
     * @param shard
     * @param map
     * @throws IOException
     */
    static void saveToSingleFile(
            @NonNull File folder,
            @NonNull String shard,
            @NonNull Map<SourceFile, SourceFile> map)
            throws IOException {
        File file = getSingleFile(folder, shard);
        file.getParentFile().mkdir();
        try (JsonWriter out = new JsonWriter(Files.newWriter(file, Charsets.UTF_8))) {
            out.setIndent(INDENT_STRING);
            out.beginArray();
            for (Map.Entry<SourceFile, SourceFile> entry : map.entrySet()) {
                out.beginObject();
                out.name(KEY_MERGED);
                mSourceFileJsonTypeAdapter.write(out, entry.getKey());
                out.name(KEY_SOURCE);
                mSourceFileJsonTypeAdapter.write(out, entry.getValue());
                out.endObject();
            }
            out.endArray();
        }
    }

    @NonNull
    static Map<SourceFile, SourceFile> loadFromSingleFile(
            @NonNull File folder,
            @NonNull String shard) {
        Map<SourceFile, SourceFile> fileMap = Maps.newConcurrentMap();
        File file = getSingleFile(folder, shard);
        if (!file.exists()) {
            return fileMap;
        }

        try (JsonReader reader = new JsonReader(Files.newReader(file, Charsets.UTF_8))) {
            reader.beginArray();
            while (reader.peek() != JsonToken.END_ARRAY) {
                reader.beginObject();
                SourceFile merged = SourceFile.UNKNOWN;
                SourceFile source = SourceFile.UNKNOWN;
                while (reader.peek() != JsonToken.END_OBJECT) {
                    String name = reader.nextName();
                    if (name.equals(KEY_MERGED)) {
                        merged = mSourceFileJsonTypeAdapter.read(reader);
                    } else if (name.equals(KEY_SOURCE)) {
                        source = mSourceFileJsonTypeAdapter.read(reader);
                    } else {
                        throw new IOException(String.format("Unexpected property: %s", name));
                    }
                }
                reader.endObject();
                fileMap.put(merged, source);
            }
            reader.endArray();
            return fileMap;
        } catch (FileNotFoundException e) {
            // Shouldn't happen unless it disappears under us.
            return fileMap;
        } catch (IOException e) {
            // TODO: trigger a non-incremental merge if this happens.
            throw new RuntimeException(e);
        }
    }
}