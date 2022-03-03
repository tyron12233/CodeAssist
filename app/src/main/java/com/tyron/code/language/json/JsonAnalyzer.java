package com.tyron.code.language.json;

import com.tyron.code.language.AbstractCodeAnalyzer;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

import java.util.Stack;

import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class JsonAnalyzer extends AbstractCodeAnalyzer<Object> {

    private final Stack<CodeBlock> mBlockLines = new Stack<>();
    private int mMaxSwitch;
    private int mCurrSwitch;

    @Override
    public Lexer getLexer(CharStream input) {
        return new JSONLexer(input);
    }

    @Override
    public void setup() {
        putColor(EditorColorScheme.TEXT_NORMAL, JSONLexer.LBRACKET,
                JSONLexer.RBRACKET, JSONLexer.LBRACE, JSONLexer.RBRACE);
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
    public boolean onNextToken(Token currentToken, Styles styles, MappedSpans.Builder colors) {
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
            case JSONLexer.RBRACE:
                if (!mBlockLines.isEmpty()) {
                    CodeBlock b = mBlockLines.pop();
                    b.endLine = line;
                    b.endColumn = column;
                    if (b.startLine != b.endLine) {
                        styles.addCodeBlock(b);
                    }
                }
                return false;
            case JSONLexer.LBRACE:
                if (mBlockLines.isEmpty()) {
                    if (mCurrSwitch > mMaxSwitch) {
                        mMaxSwitch = mCurrSwitch;
                    }
                    mCurrSwitch = 0;
                }
                mCurrSwitch++;
                CodeBlock block = styles.obtainNewBlock();
                block.startLine = line;
                block.startColumn = column;
                mBlockLines.push(block);
                return false;
        }
        return false;
    }

    @Override
    protected void afterAnalyze(CharSequence content, Styles styles, MappedSpans.Builder colors) {
        if (mBlockLines.isEmpty()) {
            if (mMaxSwitch > mCurrSwitch) {
                mMaxSwitch = mCurrSwitch;
            }
        }
        styles.setSuppressSwitch(mMaxSwitch + 10);
    }
}
