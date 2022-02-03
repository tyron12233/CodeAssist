package com.tyron.code.ui.editor.language.json;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.tyron.completion.java.rewrite.EditHelper;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.apache.commons.io.input.CharSequenceReader;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class JsonLanguage implements Language {

    private final Editor mEditor;

    private final JsonAnalyzer mAnalyzer;

    public JsonLanguage(Editor editor) {
        mEditor = editor;

        mAnalyzer = new JsonAnalyzer();
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return mAnalyzer;
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content, @NonNull CharPosition position, @NonNull CompletionPublisher publisher, @NonNull Bundle extraArguments) throws CompletionCancelledException {

    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        return getIndentAdvance(String.valueOf(content.getReference()), line, column);
    }

    private int getIndentAdvance(String content, int line, int column) {
        JSONLexer lexer = new JSONLexer(CharStreams.fromString(content));
        Token token;
        int advance = 0;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            if (token.getType() == JSONLexer.LBRACKET) {
                advance++;
            }
        }
        advance = Math.max(0, advance);
        return advance * 2;
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @Override
    public CharSequence format(CharSequence text) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonElement jsonElement = JsonParser.parseString(text.toString());
            return gson.toJson(jsonElement);
        } catch (Throwable e) {
            // format error, return the original string
            return text;
        }
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[] {
                new IndentHandler("{", "}"),
                new IndentHandler("[", "]")
        };
    }

    @Override
    public void destroy() {

    }

    class IndentHandler implements NewlineHandler {

        private final String start;
        private final String end;

        public IndentHandler(String start, String end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.endsWith(start) && afterText.startsWith(end);
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceBefore = getIndentAdvance(beforeText, -1, -1);
            int advanceAfter = getIndentAdvance(afterText, -1, -1);
            String text;
            StringBuilder sb = new StringBuilder("\n")
                    .append(TextUtils.createIndent(count + advanceBefore, tabSize, useTab()))
                    .append('\n')
                    .append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = text.length() + 1;
            return new NewlineHandleResult(sb, shiftLeft);
        }
    }
}
