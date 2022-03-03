package com.tyron.code.language;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.apache.commons.io.input.CharSequenceReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public abstract class AbstractCodeAnalyzer<T> extends DiagnosticAnalyzeManager<T> {

    private final Map<Integer, Integer> mColorMap = new HashMap<>();

    private StyleReceiver mReceiver;
    private Token mPreviousToken;
    private Styles mLastStyles;
    protected List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();

    public AbstractCodeAnalyzer() {
        setup();
    }

    @Override
    public void setReceiver(@Nullable StyleReceiver receiver) {
        super.setReceiver(receiver);

        mReceiver = receiver;
    }

    @Override
    public void insert(CharPosition start, CharPosition end, CharSequence insertedContent) {
        rerunWithBg();
    }

    @Override
    public void delete(CharPosition start, CharPosition end, CharSequence deletedContent) {
        rerunWithBg();
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        super.reset(content, extraArguments);
    }

    @Override
    public void setDiagnostics(Editor editor, List<DiagnosticWrapper> diagnostics) {
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

    public abstract void analyzeInBackground(CharSequence contents);

    public Integer getColor(int tokenType) {
        return mColorMap.get(tokenType);
    }

    /**
     * Called before {@link #analyze(StringBuilder, Delegate)}
     * is called, commonly used to clear object caches before starting the analysis
     */
    protected void beforeAnalyze() {

    }

    @Override
    protected Styles analyze(StringBuilder text, Delegate<T> delegate) {
        Styles styles = new Styles();
        boolean loaded = getExtraArguments().getBoolean("loaded", false);
        if (!loaded) {
            return styles;
        }
        beforeAnalyze();

        MappedSpans.Builder result = new MappedSpans.Builder(1024);

        try {
            Lexer lexer = getLexer(CharStreams.fromReader(new CharSequenceReader(text)));
            while (!delegate.isCancelled()) {
                Token token = lexer.nextToken();
                if (token == null) {
                    break;
                }
                if (token.getType() == Token.EOF) {
                    break;
                }

                boolean skip = onNextToken(token, styles, result);
                if (skip) {
                    mPreviousToken = token;
                    continue;
                }

                Integer id = getColor(token.getType());
                if (id == null) {
                    id = EditorColorScheme.TEXT_NORMAL;
                }
                result.addIfNeeded(token.getLine() - 1, token.getCharPositionInLine(), id);

                mPreviousToken = token;
            }

            if (mPreviousToken != null) {
                result.determine(mPreviousToken.getLine() - 1);
            }

            styles.spans = result.build();
            styles.finishBuilding();
            afterAnalyze(text, styles, result);

            if (mShouldAnalyzeInBg) {
                analyzeInBackground(text);
            }
        } catch (IOException e) {
            // ignored
        }

        mLastStyles = styles;
        return styles;
    }

    @Nullable
    protected Styles getLastStyles() {
        return mLastStyles;
    }

    /**
     * Called after the analysis has been done, used to finalize the {@link MappedSpans.Builder}
     */
    protected void afterAnalyze(CharSequence content, Styles styles, MappedSpans.Builder colors) {

    }

    @Nullable
    public Token getPreviousToken() {
        return mPreviousToken;
    }

    /**
     * Called when the lexer has moved to the next token
     * @param currentToken the current token
     * @param styles
     * @param colors the current colors object, can be modified
     * @return true if the analyzer should skip on the next token
     */
    public boolean onNextToken(Token currentToken, Styles styles, MappedSpans.Builder colors) {
        return false;
    }

    public void update(Styles styles) {
        mReceiver.setStyles(this, styles);
    }
}
