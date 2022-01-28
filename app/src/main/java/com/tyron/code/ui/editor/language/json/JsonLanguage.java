package com.tyron.code.ui.editor.language.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonToken;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.json.JSONTokener;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.interfaces.NewlineHandler;
import io.github.rosemoe.sora.langs.EmptyLanguage;
import io.github.rosemoe.sora.langs.java.JavaTextTokenizer;
import io.github.rosemoe.sora.langs.java.Tokens;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class JsonLanguage implements EditorLanguage {

    private final CodeEditor mEditor;

    public JsonLanguage(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public CodeAnalyzer getAnalyzer() {
        return new JsonAnalyzer();
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return new EmptyLanguage.EmptyAutoCompleteProvider();
    }

    @Override
    public boolean isAutoCompleteChar(char ch) {
        return false;
    }

    @Override
    public int getIndentAdvance(String content) {
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
        public HandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceBefore = getIndentAdvance(beforeText);
            int advanceAfter = getIndentAdvance(afterText);
            String text;
            StringBuilder sb = new StringBuilder("\n")
                    .append(TextUtils.createIndent(count + advanceBefore, tabSize, useTab()))
                    .append('\n')
                    .append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = text.length() + 1;
            return new HandleResult(sb, shiftLeft);
        }
    }
}
