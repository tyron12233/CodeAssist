package com.tyron.kotlin_completion.position;

import com.tyron.completion.model.Range;

import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import kotlin.text.StringsKt;

public class Position {
    public static int offset(String content, int line, int character) {
        assert !content.contains("\r");

        try {
            BufferedReader reader = new BufferedReader(new StringReader(content));
            int offset = 0;
            int lineOffset = 0;

            while (lineOffset < line) {
                int nextChar = reader.read();

                if (nextChar == -1) {
                    throw new RuntimeException("Reached end of line while parsing");
                }

                if (nextChar == '\n') {
                    lineOffset++;
                }
                offset++;
            }

            int charOffset = 0;
            while (charOffset < character) {
                int nextChar = reader.read();

                if (nextChar == -1) {
                    throw new RuntimeException("Reached end of character while parsing");
                }

                charOffset++;
                offset++;
            }

            return offset;
        } catch (Exception e) {
            return -1;
        }
    }

    public static Pair<TextRange, TextRange> changedRegion(String oldContent, String newContent) {
        if (oldContent.equals(newContent)) {
            return null;
        }

        int prefix = StringsKt.commonPrefixWith(oldContent, newContent, false).length();
        int suffix = StringsKt.commonSuffixWith(oldContent, newContent, false).length();
        int oldEnd = Math.max(oldContent.length() - suffix, prefix);
        int newEnd = Math.max(newContent.length() - suffix, prefix);

        return Pair.create(new TextRange(prefix, oldEnd), new TextRange(prefix, newEnd));
    }


    public static com.tyron.completion.model.Position position(String content, int offset) throws IOException {
        StringReader reader = new StringReader(content);
        int line = 0;
        int c = 0;

        int find = 0;
        while (find < offset) {
            int nextChar = reader.read();

            if (nextChar == -1) {
                throw new IllegalArgumentException("Reached end of file before reaching offset " + offset);
            }

            find++;
            c++;

            if (nextChar == '\n') {
                line++;
                c = 0;
            }
        }

        return new com.tyron.completion.model.Position(line, c);
    }
    public static Range range(String content, TextRange range) {
        try {
            return new Range(position(content, range.getStartOffset()), position(content, range.getEndOffset()));
        } catch (IOException e) {
            return Range.NONE;
        }
    }
}
