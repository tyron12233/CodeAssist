package com.tyron.builder.compiler.manifest.blame;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;

public class Message {

    private final Kind kind;
    private final String text;
    private final List<SourceFilePosition> sourceFilePositions;
    private final String rawMessage;
    private final String toolName;
    
    public Message(Kind kind, String text) {
        this(kind, text, text, null, ImmutableList.of(SourceFilePosition.UNKNOWN));
    }

    public Message(Kind kind, String text, String rawMessage, String toolName, List<SourceFilePosition> sourceFilePositions) {
        if (sourceFilePositions.isEmpty()) {
            throw new IllegalArgumentException("SourceFilePositions cannot be empty");
        }

        this.kind = kind;
        this.text = text;
        this.sourceFilePositions = sourceFilePositions;
        this.rawMessage = rawMessage;
        this.toolName = toolName;
    }

    public Message(Kind kind, String text, String rawMessage, SourceFilePosition sourceFilePosition, SourceFilePosition... sourceFilePositions) {
        this(kind, text, rawMessage, null, new ImmutableList.Builder<SourceFilePosition>()
                .add(sourceFilePosition)
                .add(sourceFilePositions)
                .build());
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public String getToolName() {
        return toolName;
    }

    public String getText() {
        return text;
    }

    public List<SourceFilePosition> getSourceFilePositions() {
        return sourceFilePositions;
    }

    public Kind getKind() {
        return kind;
    }

    private String sourcePath;

    public String getSourcePath() {
        if (sourcePath == null) {
            File file = sourceFilePositions.get(0).getFile().getSourceFile();
            if (file == null) {
                return null;
            }
            sourcePath = file.getAbsolutePath();
        }
        return sourcePath;
    }

    public enum Kind {
        ERROR, WARNING, INFO, STATISTICS, UNKNOWN, SIMPLE;

        public static Kind findIgnoringCase(String string, Kind defaultKind) {
            for (Kind kind : values()) {
                if (kind.toString().equalsIgnoreCase(string)) {
                    return kind;
                }
            }
            return defaultKind;
        }
    }
}
