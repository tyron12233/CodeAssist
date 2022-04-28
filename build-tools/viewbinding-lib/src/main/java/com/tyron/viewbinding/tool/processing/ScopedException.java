/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.viewbinding.tool.processing;


import com.tyron.viewbinding.tool.store.Location;
import com.tyron.viewbinding.tool.util.L;
import com.android.annotations.NonNullByDefault;
import com.android.annotations.Nullable;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An exception that contains scope information.
 */
public class ScopedException extends RuntimeException {
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r?\\n");
    private static final String ERROR_LOG_PREFIX = "[databinding] ";
    private static boolean sEncodeOutput = false;
    private ScopedErrorReport mScopedErrorReport;
    private String mScopeLog;

    @NonNullByDefault
    private static final class FileLocation {
        @SerializedName("line0")
        public final int lineStart;
        @SerializedName("col0")
        public final int colStart;
        @SerializedName("line1")
        public final int lineEnd;
        @SerializedName("col1")
        public final int colEnd;

        private FileLocation(Location location) {
            lineStart = location.startLine;
            colStart = location.startOffset;
            lineEnd = location.endLine;
            colEnd = location.endOffset;
        }

        public Location toLocation() {
            return new Location(lineStart, colStart, lineEnd, colEnd);
        }
    }

    @NonNullByDefault
    private static final class EncodedMessage {
        @SerializedName("msg")
        public final String message;
        @SerializedName("file")
        public final String filePath;
        @SerializedName("pos")
        public final List<FileLocation> positions = new ArrayList<>();

        private EncodedMessage(String message, String filePath) {
            this.message = message;
            this.filePath = filePath;
        }
    }

    public ScopedException(String message, Object... args) {
        this(null, message, args);
    }

    public ScopedException(@Nullable Throwable cause, String message, Object... args) {
        super(message == null ? "unknown data binding exception" :
                args.length == 0 ? message : String.format(message, args),
                cause);
        mScopedErrorReport = Scope.createReport();
        mScopeLog = L.isDebugEnabled() ? Scope.produceScopeLog() : null;
    }

    ScopedException(String message, ScopedErrorReport scopedErrorReport) {
        super(message);
        mScopedErrorReport = scopedErrorReport;
    }

    public String getBareMessage() {
        return super.getMessage();
    }

    @Override
    public String getMessage() {
        return sEncodeOutput ? createEncodedMessage() : createHumanReadableMessage();
    }

    public String createHumanReadableMessage() {
        ScopedErrorReport scopedError = getScopedErrorReport();
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: ").append(super.getMessage())
            .append(" file://").append(scopedError.getFilePath());
        if (scopedError.getLocations() != null && !scopedError.getLocations().isEmpty()) {
            sb.append(" Line:").append(scopedError.getLocations().get(0).startLine);
        }
        return sb.toString();
    }

    private String createEncodedMessage() {
        ScopedErrorReport scopedError = getScopedErrorReport();

        EncodedMessage encoded = new EncodedMessage(super.getMessage(), scopedError.getFilePath());
        if (scopedError.getLocations() != null) {
            for (Location location : scopedError.getLocations()) {
                encoded.positions.add(new FileLocation(location));
            }
        }

        Gson gson = new Gson();
        return ERROR_LOG_PREFIX + gson.toJson(encoded);
    }

    public ScopedErrorReport getScopedErrorReport() {
        return mScopedErrorReport;
    }

    public boolean isValid() {
        return mScopedErrorReport.isValid();
    }

    private static ScopedException parseJson(String jsonError) throws JsonSyntaxException {
        Gson gson = new Gson();
        EncodedMessage encoded = gson.fromJson(jsonError, EncodedMessage.class);
        List<Location> locations = encoded.positions
          .stream()
          .map(FileLocation::toLocation)
          .collect(Collectors.toList());

        return new ScopedException(
          encoded.message,
            new ScopedErrorReport(Strings.isNullOrEmpty(encoded.filePath) ? null : encoded.filePath,
                locations));
    }

    /**
     * Given encoded output generated by {@link #getMessage()}, extract out the list of
     * {@link ScopedException}s contained within.
     */
    public static List<ScopedException> extractErrors(String output) {
        Iterable<String> lines = Splitter.on(NEWLINE_PATTERN).omitEmptyStrings().trimResults()
            .split(output);
        List<ScopedException> errors = new ArrayList<>();
        for (String line : lines) {
            if (!line.contains(ERROR_LOG_PREFIX)) {
                continue;
            }

            line = line.substring(line.indexOf(ERROR_LOG_PREFIX) + ERROR_LOG_PREFIX.length());
            try {
                errors.add(parseJson(line));
            }
            catch (JsonSyntaxException ignored) {
            }
        }

        return errors;
    }

    public static void encodeOutput(boolean encodeOutput) {
        sEncodeOutput = encodeOutput;
    }

    public static boolean isEncodeOutput() {
        return sEncodeOutput;
    }
}
