package com.tyron.code.ui.editor.impl.text.rosemoe;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.editor.Content;

import java.util.stream.IntStream;

import io.github.rosemoe.sora.text.CharPosition;

public class ContentWrapper implements Content {

    private final io.github.rosemoe.sora.text.Content mContent;

    public ContentWrapper(io.github.rosemoe.sora.text.Content content) {
        mContent = content;
    }

    @Override
    public int length() {
        return mContent.length();
    }

    @Override
    public char charAt(int index) {
        return mContent.charAt(index);
    }

    @NonNull
    @Override
    public CharSequence subSequence(int start, int end) {
        return mContent.subSequence(start, end);
    }

    @NonNull
    @Override
    public IntStream chars() {
        return mContent.chars();
    }

    @NonNull
    @Override
    public IntStream codePoints() {
        return mContent.codePoints();
    }

    @Override
    public int hashCode() {
        return mContent.hashCode();
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override
    public boolean equals(@Nullable Object obj) {
        return mContent.equals(obj);
    }

    @NonNull
    @Override
    public String toString() {
        return mContent.toString();
    }

    @Override
    public boolean canRedo() {
        return mContent.canRedo();
    }

    @Override
    public void redo() {
        mContent.redo();
    }

    @Override
    public boolean canUndo() {
        return mContent.canUndo();
    }

    @Override
    public void undo() {
        mContent.undo();
    }

    @Override
    public int getLineCount() {
        return mContent.getLineCount();
    }

    @Override
    public String getLineString(int line) {
        return mContent.getLineString(line);
    }

    @Override
    public void insert(int line, int column, CharSequence text) {
        mContent.insert(line, column, text);
    }

    @Override
    public void insert(int index, CharSequence string) {
        CharPosition startPos = mContent.getIndexer()
                .getCharPosition(index);
        insert(startPos.getLine(), startPos.getColumn(), string);
    }

    @Override
    public void delete(int start, int end) {
        mContent.delete(start, end);
    }

    @Override
    public void replace(int start, int end, CharSequence text) {
        CharPosition startPos = mContent.getIndexer()
                .getCharPosition(start);
        CharPosition endPos = mContent.getIndexer()
                .getCharPosition(end);
        mContent.replace(startPos.getLine(), startPos.getColumn(),
                         endPos.getLine(), endPos.getColumn(), text);
    }
}
