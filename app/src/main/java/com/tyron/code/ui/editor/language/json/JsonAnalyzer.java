package com.tyron.code.ui.editor.language.json;

import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;
import com.tyron.code.ui.editor.language.groovy.GroovyLexer;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

import java.util.Stack;

import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class JsonAnalyzer extends AbstractCodeAnalyzer {

    private final Stack<BlockLine> mBlockLines = new Stack<>();
    private int mMaxSwitch;
    private int mCurrSwitch;

    @Override
    public Lexer getLexer(CharStream input) {
        return new JSONLexer(input);
    }

    @Override
    public void setup() {
        putColor(EditorColorScheme.TEXT_NORMAL, JSONLexer.LBRACKET,
                JSONLexer.RBRACKET);
        putColor(EditorColorScheme.KEYWORD, JSONLexer.TRUE,
                JSONLexer.FALSE, JSONLexer.NULL,
                JSONLexer.COLON, JSONLexer.COMMA);
        putColor(EditorColorScheme.OPERATOR, JSONLexer.COLON);
        putColor(EditorColorScheme.LITERAL, JSONLexer.NUMBER);
        putColor(EditorColorScheme.ATTRIBUTE_NAME, JSONLexer.STRING);
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {

    }

    @Override
    protected void beforeAnalyze() {
        mBlockLines.clear();
        mMaxSwitch = 1;
        mCurrSwitch = 0;
    }

    @Override
    public boolean onNextToken(Token currentToken, TextAnalyzeResult colors) {
        int line = currentToken.getLine() - 1;
        int column = currentToken.getCharPositionInLine();

        switch (currentToken.getType()) {
            case JSONLexer.STRING:
                Token previousToken = getPreviousToken();
                if (previousToken != null) {
                    if (previousToken.getType() == JSONLexer.COLON) {
                        colors.addIfNeeded(line, column, EditorColorScheme.LITERAL);
                        return true;
                    }
                }
                break;
            case JSONLexer.RBRACKET:
                if (!mBlockLines.isEmpty()) {
                    BlockLine b = mBlockLines.pop();
                    b.endLine = line;
                    b.endColumn = column;
                    if (b.startLine != b.endLine) {
                        colors.addBlockLine(b);
                    }
                }
                return false;
            case JSONLexer.LBRACKET:
                if (mBlockLines.isEmpty()) {
                    if (mCurrSwitch > mMaxSwitch) {
                        mMaxSwitch = mCurrSwitch;
                    }
                    mCurrSwitch = 0;
                }
                mCurrSwitch++;
                BlockLine block = colors.obtainNewBlock();
                block.startLine = line;
                block.startColumn = column;
                mBlockLines.push(block);
                return false;
        }
        return false;
    }

    @Override
    protected void afterAnalyze(TextAnalyzeResult colors) {
        if (mBlockLines.isEmpty()) {
            if (mMaxSwitch > mCurrSwitch) {
                mMaxSwitch = mCurrSwitch;
            }
        }
        colors.setSuppressSwitch(mMaxSwitch + 10);
    }
}
