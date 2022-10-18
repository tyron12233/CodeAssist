package com.tyron.builder.gradle.internal.pipeline;

import static com.android.SdkConstants.DOT_JAR;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.Format;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.gradle.internal.InternalScope;
import com.android.utils.FileUtils;
import com.google.common.base.Charsets;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represent a sub-stream in the main intermediate stream, with its properties (name, types,
 * scopes,...)
 */
public final class SubStream {
    public static final String FN_FOLDER_CONTENT = "__content__.json";

    @NonNull private final String name;

    private final int index;

    private final String filename;

    @NonNull private final Set<? super QualifiedContent.Scope> scopes;

    @NonNull private final Set<QualifiedContent.ContentType> types;

    @NonNull private final Format format;

    private final boolean present;

    SubStream(
            @NonNull String name,
            int index,
            @NonNull Set<? super QualifiedContent.Scope> scopes,
            @NonNull Set<QualifiedContent.ContentType> types,
            @NonNull Format format,
            boolean present) {
        this.name = name;
        this.index = index;
        this.scopes = scopes;
        this.types = types;
        this.format = format;
        this.present = present;
        filename = computeFilename();
    }

    @NonNull
    public String getName() {
        return name;
    }

    @NonNull
    public Set<? super QualifiedContent.Scope> getScopes() {
        return scopes;
    }

    @NonNull
    public Set<QualifiedContent.ContentType> getTypes() {
        return types;
    }

    @NonNull
    public Format getFormat() {
        return format;
    }

    public int getIndex() {
        return index;
    }

    public String getFilename() {
        return filename;
    }

    public boolean isPresent() {
        return present;
    }

    public static Collection<SubStream> loadSubStreams(@NonNull File rootFolder) {
        final File jsonFile = new File(rootFolder, FN_FOLDER_CONTENT);
        if (!jsonFile.isFile()) {
            return ImmutableList.of();
        }

        try (FileReader reader = new FileReader(jsonFile)) {

            GsonBuilder gsonBuilder = new GsonBuilder();
            gsonBuilder.registerTypeAdapter(SubStream.class, new SubStreamAdapter());
            Gson gson = gsonBuilder.create();

            Type recordType = new TypeToken<List<SubStream>>() {}.getType();
            return gson.fromJson(reader, recordType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void save(@NonNull Collection<SubStream> subStreams, @NonNull File rootFolder)
            throws IOException {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(SubStream.class, new SubStreamAdapter());
        Gson gson = gsonBuilder.create();

        // just in case
        FileUtils.mkdirs(rootFolder);
        Files.asCharSink(new File(rootFolder, FN_FOLDER_CONTENT), Charsets.UTF_8)
                .write(gson.toJson(subStreams));
    }

    private String computeFilename() {
        if (format == Format.DIRECTORY) {
            return Integer.toString(index);
        }

        return Integer.toString(index) + DOT_JAR;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SubStream subStream = (SubStream) o;
        return index == subStream.index
                && present == subStream.present
                && Objects.equals(name, subStream.name)
                && Objects.equals(scopes, subStream.scopes)
                && Objects.equals(types, subStream.types)
                && format == subStream.format;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, index, scopes, types, format, present);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("name", name)
                .add("index", index)
                .add("filename", filename)
                .add("scopes", scopes)
                .add("types", types)
                .add("format", format)
                .add("present", present)
                .toString();
    }

    public SubStream duplicateWithPresent(boolean exists) {
        return new SubStream(name, index, scopes, types, format, exists);
    }

    private static final class SubStreamAdapter extends TypeAdapter<SubStream> {

        @Override
        public void write(JsonWriter out, SubStream value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginObject();

            out.name("name").value(value.getName());

            out.name("index").value(value.getIndex());

            out.name("scopes").beginArray();
            Set<? super QualifiedContent.Scope> scopes = value.getScopes();
            for (Object scope : scopes) {
                out.value(scope.toString());
            }
            out.endArray();

            out.name("types").beginArray();
            for (QualifiedContent.ContentType type : value.getTypes()) {
                out.value(type.toString());
            }
            out.endArray();

            out.name("format").value(value.getFormat().toString());

            out.name("present").value(value.present);

            out.endObject();
        }

        @Override
        public SubStream read(JsonReader in) throws IOException {
            in.beginObject();

            String name = null;
            int index = -1;
            Set<QualifiedContent.ScopeType> scopes = Sets.newHashSet();
            Set<QualifiedContent.ContentType> types = Sets.newHashSet();
            Format format = null;
            boolean present = false;

            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "name":
                        name = in.nextString();
                        break;
                    case "index":
                        index = in.nextInt();
                        break;
                    case "scopes":
                        readScopes(in, scopes);
                        break;
                    case "types":
                        readTypes(in, types);
                        break;
                    case "format":
                        format = Format.valueOf(in.nextString());
                        break;
                    case "present":
                        present = in.nextBoolean();
                        break;
                }
            }
            in.endObject();

            Preconditions.checkNotNull(name);
            Preconditions.checkNotNull(format);
            Preconditions.checkState(index >= 0);

            return new SubStream(name, index, scopes, types, format, present);
        }

        private static void readScopes(JsonReader in, Set<QualifiedContent.ScopeType> scopes)
                throws IOException {
            in.beginArray();
            while (in.hasNext()) {
                String scopeName = in.nextString();

                QualifiedContent.ScopeType scope;
                try {
                    scope = QualifiedContent.Scope.valueOf(scopeName);
                } catch (IllegalArgumentException e) {
                    scope = InternalScope.valueOf(scopeName);
                }

                scopes.add(scope);
            }

            in.endArray();
        }

        private static void readTypes(JsonReader in, Set<QualifiedContent.ContentType> types)
                throws IOException {
            in.beginArray();
            while (in.hasNext()) {
                String typeName = in.nextString();

                QualifiedContent.ContentType type;
                try {
                    type = QualifiedContent.DefaultContentType.valueOf(typeName);
                } catch (IllegalArgumentException e) {
                    type = ExtendedContentType.valueOf(typeName);
                }

                types.add(type);
            }

            in.endArray();
            ;
        }
    }
}
