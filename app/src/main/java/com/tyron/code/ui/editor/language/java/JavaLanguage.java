package com.tyron.code.ui.editor.language.java;

import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.interfaces.NewlineHandler;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.langs.java.JavaTextTokenizer;
import io.github.rosemoe.sora.langs.java.Tokens;

import android.util.Log;

import com.google.common.collect.Range;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.google.googlejavaformat.java.JavaFormatterOptions;

import java.util.ArrayList;
import java.util.Collection;

public class JavaLanguage implements EditorLanguage {
    
    private CodeEditor mEditor;

    private final CodeAnalyzer mAnalyzer;
    private final AutoCompleteProvider mAutoCompleteProvider;

    public JavaLanguage(CodeEditor editor) {
        mEditor = editor;
        mAnalyzer = new JavaAnalyzer(editor);
        mAutoCompleteProvider = new JavaAutoCompleteProvider(mEditor);
    }
    
    @Override
    public CodeAnalyzer getAnalyzer() {
        return mAnalyzer;
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return mAutoCompleteProvider;
    }

    @Override
    public boolean isAutoCompleteChar(char p1) {
        return p1 == '.' || MyCharacter.isJavaIdentifierPart(p1);
    }

    @Override
    public int getIndentAdvance(String p1) {
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(p1);
        Tokens token;
        int advance = 0;
        while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
            switch (token) {
                case LBRACE:
                    advance++;
                    break;
                    /*case RBRACE:
                     advance--;
                     break;*/
            }
        }
        advance = Math.max(0, advance);
        return advance * 4;
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @Override
    public CharSequence format(CharSequence p1) {
        try {
            return new Formatter(JavaFormatterOptions.builder()
                    .style(JavaFormatterOptions.Style.AOSP).build()).formatSourceAndFixImports(p1.toString());
        } catch (FormatterException e) {
            Log.e("JavaFormatter", e.getMessage());
            return p1;
        }
    }

    @Override
    public CharSequence format(CharSequence contents, int start, int end) {
        JavaFormatterOptions options = JavaFormatterOptions.builder()
                .style(JavaFormatterOptions.Style.AOSP)
                .build();
        Formatter formatter = new Formatter(options);
        Range<Integer> range = Range.closed(start, end);
        Collection<Range<Integer>> ranges = new ArrayList<>();
        ranges.add(range);
        try {
            return formatter.formatSource(contents.toString(), ranges);
        } catch (FormatterException e) {
            Log.d("Formatter", "Unable to format file", e);
            return contents;
        }
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }
    
    private final NewlineHandler[] newLineHandlers = new NewlineHandler[]{
            new BraceHandler(), new TwoIndentHandler(), new JavaDocStartHandler(), new JavaDocHandler()};
    
    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newLineHandlers;
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
		public NewlineHandler.HandleResult handleNewline(String beforeText, String afterText, int tabSize) {
			int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText) + (4 * 2);
            String text;
            StringBuilder sb = new StringBuilder()
                .append('\n')
                .append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = 0;
            return new HandleResult(sb, shiftLeft);
		}

		
	}
    
    class BraceHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.endsWith("{") && afterText.startsWith("}");
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

    class JavaDocStartHandler implements NewlineHandler {

        private boolean shouldCreateEnd = true;

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim().startsWith("/**");
        }

        @Override
        public HandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText);
            String text = "";
            StringBuilder sb = new StringBuilder()
                    .append("\n")
                    .append(TextUtils.createIndent(count + advanceAfter, tabSize, useTab()))
                    .append(" * ");
            if (shouldCreateEnd) {
                sb.append("\n").append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()))
                        .append(" */");
            }
            return new HandleResult(sb, text.length() + 4);
        }
    }

    class JavaDocHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim().startsWith("*");
        }

        @Override
        public HandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText);
            StringBuilder sb = new StringBuilder()
                    .append("\n")
                    .append(TextUtils.createIndent(count + advanceAfter, tabSize, useTab()))
                    .append("* ");
            return new HandleResult(sb, 0);
        }
    }
}
