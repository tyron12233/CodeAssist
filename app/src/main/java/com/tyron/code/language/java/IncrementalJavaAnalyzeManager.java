package com.tyron.code.language.java;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.lang.java.lexer.JavaLexer;
import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.lexer.LexerPosition;
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel;

import java.util.List;

import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

public class IncrementalJavaAnalyzeManager
        implements IncrementalAnalyzeManager<LexerPosition, Object> {

    private final Lexer mLexer;

    public IncrementalJavaAnalyzeManager() {
        mLexer = new JavaLexer(LanguageLevel.HIGHEST);
    }

    @Override
    public LexerPosition getInitialState() {
        return mLexer.getCurrentPosition();
    }

    @Override
    public LineTokenizeResult<LexerPosition, Object> getState(int line) {
        return null;
    }

    @Override
    public boolean stateEquals(LexerPosition state, LexerPosition another) {
        return state.equals(another);
    }

    @Override
    public LineTokenizeResult<LexerPosition, Object> tokenizeLine(CharSequence line, LexerPosition state) {
        return null;
    }

    @Override
    public List<Span> generateSpansForLine(LineTokenizeResult<LexerPosition, Object> tokens) {
        return null;
    }

    @Override
    public void setReceiver(@Nullable StyleReceiver receiver) {

    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        mLexer.start(content.getReference().toString());
    }

    @Override
    public void insert(CharPosition start, CharPosition end, CharSequence insertedContent) {

    }

    @Override
    public void delete(CharPosition start, CharPosition end, CharSequence deletedContent) {

    }

    @Override
    public void rerun() {

    }

    @Override
    public void destroy() {

    }
}
