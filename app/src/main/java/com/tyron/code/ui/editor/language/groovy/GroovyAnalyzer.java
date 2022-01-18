package com.tyron.code.ui.editor.language.groovy;

import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

import java.util.Stack;

import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class GroovyAnalyzer extends AbstractCodeAnalyzer {

    private final CodeEditor mEditor;

    int maxSwitch = 1;
    int currSwitch;
    private final Stack<BlockLine> mBlockLines = new Stack<>();

    public GroovyAnalyzer(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public Lexer getLexer(CharStream input) {
        return new GroovyLexer(input);
    }

    @Override
    public void setup() {
        putColor(EditorColorScheme.KEYWORD, GroovyLexer.KW_DO,
                GroovyLexer.KW_ABSTRACT, GroovyLexer.KW_FALSE,
                GroovyLexer.KW_TRUE, GroovyLexer.KW_CASE,
                GroovyLexer.KW_CATCH, GroovyLexer.KW_AS,
                GroovyLexer.KW_WHILE, GroovyLexer.KW_TRY,
                GroovyLexer.KW_BREAK, GroovyLexer.KW_THIS,
                GroovyLexer.KW_ASSERT, GroovyLexer.KW_VOLATILE,
                GroovyLexer.KW_NULL, GroovyLexer.KW_NEW,
                GroovyLexer.KW_RETURN, GroovyLexer.KW_PACKAGE,
                GroovyLexer.KW_FOR, GroovyLexer.KW_IF,
                GroovyLexer.SEMICOLON);
        putColor(EditorColorScheme.LITERAL, GroovyLexer.STRING,
                GroovyLexer.INTEGER);
        putColor(EditorColorScheme.OPERATOR, GroovyLexer.PLUS,
                GroovyLexer.MINUS, GroovyLexer.MULT, GroovyLexer.DIV,
                GroovyLexer.PLUS_ASSIGN, GroovyLexer.MINUS_ASSIGN,
                GroovyLexer.MULT_ASSIGN, GroovyLexer.DIV_ASSIGN,
                GroovyLexer.MOD, GroovyLexer.MOD_ASSIGN,
                GroovyLexer.OR, GroovyLexer.AND, GroovyLexer.BAND,
                GroovyLexer.BAND_ASSIGN, GroovyLexer.LSHIFT,
                GroovyLexer.LSHIFT_ASSIGN, GroovyLexer.RSHIFT_ASSIGN,
                GroovyLexer.XOR_ASSIGN, GroovyLexer.XOR);
        putColor(EditorColorScheme.IDENTIFIER_NAME, GroovyLexer.IDENTIFIER);
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {

    }

    @Override
    protected void beforeAnalyze() {
        mBlockLines.clear();
        maxSwitch = 1;
        currSwitch = 0;
    }

    @Override
    public boolean onNextToken(Token currentToken, TextAnalyzeResult colors) {
        int line = currentToken.getLine() - 1;
        int column = currentToken.getCharPositionInLine();

        switch (currentToken.getType()) {
            case GroovyLexer.RCURVE:
                if (!mBlockLines.isEmpty()) {
                    BlockLine b = mBlockLines.pop();
                    b.endLine = line;
                    b.endColumn = column;
                    if (b.startLine != b.endLine) {
                        colors.addBlockLine(b);
                    }
                }
                return true;
            case GroovyLexer.LCURVE:
                if (mBlockLines.isEmpty()) {
                    if (currSwitch > maxSwitch) {
                        maxSwitch = currSwitch;
                    }
                    currSwitch = 0;
                }
                currSwitch++;
                BlockLine block = colors.obtainNewBlock();
                block.startLine = line;
                block.startColumn = column;
                mBlockLines.push(block);
                return true;
        }
        return false;
    }

    @Override
    protected void afterAnalyze(TextAnalyzeResult colors) {
        if (mBlockLines.isEmpty()) {
            if (currSwitch > maxSwitch) {
                maxSwitch = currSwitch;
            }
        }
        colors.setSuppressSwitch(maxSwitch + 10);
    }
}
