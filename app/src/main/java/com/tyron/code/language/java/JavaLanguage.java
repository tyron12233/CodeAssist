package com.tyron.code.language.java;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.common.collect.Range;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.google.googlejavaformat.java.JavaFormatterOptions;
import com.tyron.code.language.CompletionItemWrapper;
import com.tyron.code.language.EditorFormatter;
import com.tyron.code.analyzer.BaseTextmateAnalyzer;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import java.util.ArrayList;
import java.util.Collection;

import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class JavaLanguage implements Language, EditorFormatter {

    private final Editor mEditor;

    private final BaseTextmateAnalyzer mAnalyzer;

    public JavaLanguage(Editor editor) {
        mEditor = editor;
        mAnalyzer = JavaAnalyzer.create(editor);
    }

    public boolean isAutoCompleteChar(char p1) {
        return p1 == '.' || MyCharacter.isJavaIdentifierPart(p1);
    }

    public int getIndentAdvance(String p1) {
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(p1);
        Tokens token;
        int advance = 0;
        while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
            switch (token) {
                case LBRACE:
                    advance++;
                    break;
            }
        }
        return (advance * getTabWidth());
    }

    public int getFormatIndent(String line) {
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(line);
        Tokens token;
        int advance = 0;
        while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
            switch (token) {
                case LBRACE:
                    advance++;
                    break;
                case RBRACE:
                    advance--;
            }
        }
        return (advance * getTabWidth());
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return mAnalyzer;
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_SLIGHT;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content,
                                    @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) throws CompletionCancelledException {
        char c = content.charAt(position.getIndex() - 1);
        if (!isAutoCompleteChar(c)) {
            return;
        }
        String prefix = CompletionHelper.computePrefix(content, position, this::isAutoCompleteChar);
        JavaAutoCompleteProvider provider = new JavaAutoCompleteProvider(mEditor);
        CompletionList list = provider.getCompletionList(prefix, position.getLine(), position.getColumn());
        if (list == null) {
            return;
        }
        for (CompletionItem item : list.getItems()) {
            CompletionItemWrapper wrapper = new CompletionItemWrapper(item);
            publisher.addItem(wrapper);
        }
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        String text = content.getLine(line).substring(0, column);
        return getIndentAdvance(text);
    }

    @Override
    public boolean useTab() {
        return true;
    }

    public int getTabWidth() {
        return 4;
    }


    @Override
    public CharSequence format(CharSequence p1) {
        return format(p1, 0, p1.length());
    }

    @NonNull
    @Override
    public CharSequence format(@NonNull CharSequence contents, int start, int end) {
        return com.tyron.eclipse.formatter.Formatter.format(contents.toString(),
                                                     start,
                                                     end - start);
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    private final NewlineHandler[] newLineHandlers = new NewlineHandler[]{new BraceHandler(),
            new TwoIndentHandler(), new JavaDocStartHandler(), new JavaDocHandler()};

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newLineHandlers;
    }

    @Override
    public void destroy() {

    }

    class TwoIndentHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            Log.d("BeforeText", beforeText);
            if (beforeText.replace("\r", "").trim().startsWith(".")) {
                return false;
            }
            return beforeText.endsWith(")") && !afterText.startsWith(";");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText) + (4 * 2);
            String text;
            StringBuilder sb = new StringBuilder().append('\n').append(text =
                    TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = 0;
            return new NewlineHandleResult(sb, shiftLeft);
        }


    }

    class BraceHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.endsWith("{") && afterText.startsWith("}");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceBefore = getIndentAdvance(beforeText);
            int advanceAfter = getIndentAdvance(afterText);
            String text;
            StringBuilder sb =
                    new StringBuilder("\n").append(TextUtils.createIndent(count + advanceBefore,
                            tabSize, useTab())).append('\n').append(text =
                            TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = text.length() + 1;
            return new NewlineHandleResult(sb, shiftLeft);
        }
    }

    class JavaDocStartHandler implements NewlineHandler {

        private boolean shouldCreateEnd = true;

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim().startsWith("/**");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText);
            String text = "";
            StringBuilder sb =
                    new StringBuilder().append("\n").append(TextUtils.createIndent(count + advanceAfter, tabSize, useTab())).append(" * ");
            if (shouldCreateEnd) {
                sb.append("\n").append(text = TextUtils.createIndent(count + advanceAfter,
                        tabSize, useTab())).append(" */");
            }
            return new NewlineHandleResult(sb, text.length() + 4);
        }
    }

    class JavaDocHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim().startsWith("*") && !beforeText.trim().startsWith("*/");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText);
            StringBuilder sb =
                    new StringBuilder().append("\n").append(TextUtils.createIndent(count + advanceAfter, tabSize, useTab())).append("* ");
            return new NewlineHandleResult(sb, 0);
        }
    }
}
