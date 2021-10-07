package com.tyron.code.ui.editor.language.kotlin;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.interfaces.NewlineHandler;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class KotlinLanguage implements EditorLanguage {

    private final CodeEditor mEditor;
    private final KotlinAnalyzer mAnalyzer;

    public KotlinLanguage(CodeEditor editor) {
        mEditor = editor;

        mAnalyzer = new KotlinAnalyzer();
    }

    @Override
    public CodeAnalyzer getAnalyzer() {
        return mAnalyzer;
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return new KotlinAutoCompleteProvider(mEditor);
    }

    @Override
    public boolean isAutoCompleteChar(char ch) {
        return ch == '.' || MyCharacter.isJavaIdentifierPart(ch);
    }

    @Override
    public int getIndentAdvance(String p1) {
        KotlinLexer lexer = new KotlinLexer(CharStreams.fromString(p1));
        Token token;
        int advance = 0;
        while ((token = lexer.nextToken()) != null) {
            if (token.getType() == KotlinLexer.EOF) {
                break;
            }
            if (token.getType() == KotlinLexer.LCURL) {
                advance++;
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
    public CharSequence format(CharSequence text) {
        return text;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return null;
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[0];
    }
}
