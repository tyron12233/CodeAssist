package com.tyron.code.ui.editor.language;

import androidx.annotation.Nullable;

import com.tyron.builder.model.DiagnosticWrapper;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.apache.commons.io.input.CharSequenceInputStream;
import org.apache.commons.io.input.CharSequenceReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public abstract class AbstractCodeAnalyzer implements CodeAnalyzer {

    private final Map<Integer, Integer> mColorMap = new HashMap<>();

    private List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();

    private Token mPreviousToken;

    public AbstractCodeAnalyzer() {
        setup();
    }

    @Override
    public void setDiagnostics(List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;
    }

    public void setup() {

    }

    /**
     * Convenience method to map a color id to multiple token types
     * @param id  EditorColorScheme id
     * @param tokenTypes the Token types from the lexer
     */
    protected void putColor(int id, int... tokenTypes) {
        for (int tokenType : tokenTypes) {
            putColor(id, tokenType);
        }
    }

    /**
     * Map a specific EditorColorScheme id to a token type
     * @param id The color id from {@link EditorColorScheme}
     * @param tokenType the token type from the provided lexer
     */
    protected void putColor(int id, int tokenType) {
        mColorMap.put(tokenType, id);
    }

    /**
     * @return The lexer that will be used to generate the tokens
     */
    public abstract Lexer getLexer(CharStream input);

    @Override
    public abstract void analyzeInBackground(CharSequence contents);

    public Integer getColor(int tokenType) {
        return mColorMap.get(tokenType);
    }

    /**
     * Called before {@link this#analyze(CharSequence, TextAnalyzeResult, TextAnalyzer.AnalyzeThread.Delegate)}
     * is called, commonly used to clear object caches before starting the analysis
     */
    protected void beforeAnalyze() {

    }

    @Override
    public void analyze(CharSequence content, TextAnalyzeResult result,
                        TextAnalyzer.AnalyzeThread.Delegate delegate) {
        beforeAnalyze();

        try {
            Lexer lexer = getLexer(CharStreams.fromReader(new CharSequenceReader(content)));
            while (delegate.shouldAnalyze()) {
                Token token = lexer.nextToken();
                if (token == null) {
                    break;
                }
                if (token.getType() == Token.EOF) {
                    break;
                }

                boolean skip = onNextToken(token, result);
                if (skip) {
                    continue;
                }

                Integer id = getColor(token.getType());
                if (id == null) {
                    id = EditorColorScheme.TEXT_NORMAL;
                }
                Span obtain = Span.obtain(token.getCharPositionInLine(), id);
                result.addIfNeeded(token.getLine() - 1, obtain);

                mPreviousToken = token;
            }

            if (mPreviousToken != null) {
                result.determine(mPreviousToken.getLine() - 1);
            }

            afterAnalyze(result);
        } catch (IOException e) {
            // ignored
        }
    }

    /**
     * Called after the analysis has been done, used to finalize the {@link TextAnalyzeResult}
     */
    protected void afterAnalyze(TextAnalyzeResult colors) {

    }

    @Nullable
    public Token getPreviousToken() {
        return mPreviousToken;
    }

    /**
     * Called when the lexer has moved to the next token
     * @param currentToken the current token
     * @param colors the current colors object, can be modified
     * @return true if the analyzer should skip on the next token
     */
    public boolean onNextToken(Token currentToken, TextAnalyzeResult colors) {
        return false;
    }
}
