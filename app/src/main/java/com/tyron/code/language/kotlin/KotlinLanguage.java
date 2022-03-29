package com.tyron.code.language.kotlin;

import android.content.res.AssetManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.language.CompletionItemWrapper;
import com.tyron.code.analyzer.BaseTextmateAnalyzer;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.io.InputStreamReader;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class KotlinLanguage implements Language {

    private final Editor mEditor;
    private final KotlinAnalyzer mAnalyzer;

    public KotlinLanguage(Editor editor) {
        mEditor = editor;
        AssetManager assetManager = ApplicationLoader.applicationContext.getAssets();
        mAnalyzer = KotlinAnalyzer.create(editor);
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

        // remove this when kotlin analysis is stable
        if (true) {
            return;
        }

        char c = content.charAt(position.getIndex() - 1);
        if (!isAutoCompleteChar(c)) {
            return;
        }
        String prefix = CompletionHelper.computePrefix(content, position, this::isAutoCompleteChar);
        KotlinAutoCompleteProvider provider = new KotlinAutoCompleteProvider(mEditor);
        CompletionList list =
                provider.getCompletionList(prefix, position.getLine(), position.getColumn());
        if (list != null) {
            for (CompletionItem item : list.items) {
                CompletionItemWrapper wrapper = new CompletionItemWrapper(item);
                publisher.addItem(wrapper);
            }
        }
    }

    public boolean isAutoCompleteChar(char p1) {
        return p1 == '.' || MyCharacter.isJavaIdentifierPart(p1);
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        String text = content.getLine(line)
                .substring(0, column);
        return getIndentAdvance(text);
    }

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
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return handlers;
    }

    @Override
    public void destroy() {

    }

    private final NewlineHandler[] handlers = new NewlineHandler[]{new BraceHandler()};

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
            StringBuilder sb = new StringBuilder("\n").append(
                    TextUtils.createIndent(count + advanceBefore, tabSize, useTab()))
                    .append('\n')
                    .append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = text.length() + 1;
            return new NewlineHandleResult(sb, shiftLeft);
        }
    }
}
