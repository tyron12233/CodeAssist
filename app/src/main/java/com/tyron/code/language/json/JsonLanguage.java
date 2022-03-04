package com.tyron.code.language.json;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.analyzer.BaseTextmateAnalyzer;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.io.InputStreamReader;
import java.io.StringWriter;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class JsonLanguage implements Language {

    private final Editor mEditor;

    private final BaseTextmateAnalyzer mAnalyzer;

    public JsonLanguage(Editor editor) {
        mEditor = editor;

        try {
            AssetManager assetManager = ApplicationLoader.applicationContext.getAssets();
            mAnalyzer = new BaseTextmateAnalyzer(editor, "json.tmLanguage.json",
                                                 assetManager.open(
                                                         "textmate/json" +
                                                         "/syntaxes/json" +
                                                         ".tmLanguage.json"),
                                                 new InputStreamReader(
                                                         assetManager.open(
                                                                 "textmate/json/language-configuration.json")),
                                                 ((TextMateColorScheme) ((CodeEditorView) editor).getColorScheme()).getRawTheme());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    public int getTabWidth() {
        return 2;
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        String text = content.getLine(line).substring(0, column);
        return getIndentAdvance(text);
    }

    private int getIndentAdvance(String content) {
        JSONLexer lexer = new JSONLexer(CharStreams.fromString(content));
        Token token;
        int advance = 0;
        while ((token = lexer.nextToken()).getType() != Token.EOF) {
            int type = token.getType();
            if (type == JSONLexer.LBRACKET || type == JSONLexer.LBRACE) {
                advance++;
            }
        }
        advance = Math.max(0, advance);
        return advance * getTabWidth();
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @SuppressLint("WrongThread")
    @Override
    public CharSequence format(CharSequence text) {
        try {
            Gson gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .create();
            try (StringWriter writer = new StringWriter()) {
                JsonWriter jsonWriter = gson.newJsonWriter(writer);
                jsonWriter.setIndent(useTab() ? "\t" : " ");
                gson.toJson(JsonParser.parseString(text.toString()), jsonWriter);
                return writer.toString();
            }
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
            int advanceBefore = getIndentAdvance(beforeText);
            int advanceAfter = getIndentAdvance(afterText);
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
