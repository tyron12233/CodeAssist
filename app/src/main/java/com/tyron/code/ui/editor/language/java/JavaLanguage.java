package com.tyron.code.ui.editor.language.java;

import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.widget.SymbolPairMatch;
import io.github.rosemoe.editor.interfaces.NewlineHandler;
import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.text.TextUtils;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.langs.internal.MyCharacter;
import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;

import android.util.Log;

public class JavaLanguage implements EditorLanguage {
    
    private CodeEditor mEditor;
    
    public JavaLanguage(CodeEditor editor) {
        mEditor = editor;
    }
    
    @Override
    public CodeAnalyzer getAnalyzer() {
        return JavaAnalyzer.getInstance();
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return new JavaAutoCompleteProvider(mEditor);
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
        return p1;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }
    
    private NewlineHandler[] newLineHandlers = new NewlineHandler[]{new BraceHandler(), new TwoIndentHandler()};
    
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
            StringBuilder sb = new StringBuilder("")
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
    
}
