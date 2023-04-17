package com.tyron.code.ui.editor;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.code.highlighter.JavaFileHighlighter;
import com.tyron.code.highlighter.SyntaxHighlighter;
import com.tyron.code.highlighter.SyntaxHighlighterBase;
import com.tyron.code.ui.legacyEditor.EditorChangeUtil;
import com.tyron.code.ui.legacyEditor.EditorView;
import com.tyron.editor.Editor;
import com.tyron.editor.impl.EditorImpl;

import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType;
import org.jetbrains.kotlin.com.intellij.lexer.DummyLexer;
import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.StandardProgressIndicatorBase;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PlainTextTokenTypes;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class SoraLanguageImpl implements Language {

    private static final Logger LOG = Logger.getInstance(SoraLanguageImpl.class);

    private final Disposable disposable = Disposer.newDisposable();
    private final Project project;
    private final Editor editor;
    private final AnalyzeManager analyzeManager;
    private ProgressIndicator completionProgressIndicator = new EmptyProgressIndicator();


    public SoraLanguageImpl(Project project, Editor editor, VirtualFile file) {
        this.project = project;
        this.editor = editor;
        analyzeManager = new EditorView.TestAnalyzeManager(getHighlighter(file));
    }

    @NonNull
    private static SyntaxHighlighter getHighlighter(VirtualFile file) {
        FileType fileType = file.getFileType();
        if (fileType == JavaFileType.INSTANCE) {
            return new JavaFileHighlighter();
        }

        LOG.warn("Unsupported file type: " + fileType);

        return new SyntaxHighlighterBase() {
            @NonNull
            @Override
            public Lexer getHighlightingLexer() {
                return new DummyLexer(PlainTextTokenTypes.PLAIN_TEXT);
            }

            @Override
            public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
                return new TextAttributesKey[0];
            }
        };
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzeManager;
    }

    @Override
    public int getInterruptionLevel() {
        return 0;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content,
                                    @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) throws CompletionCancelledException {
        completionProgressIndicator.cancel();
        completionProgressIndicator = new StandardProgressIndicatorBase();

        ProgressManager.getInstance().runProcess(() -> {
            try {
                EditorChangeUtil.performCompletionUnderIndicator(
                        project,
                        editor,
                        publisher,
                        disposable
                );
            } catch (ProcessCanceledException ignored) {

            }
        }, completionProgressIndicator);
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        return 0;
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @NonNull
    @Override
    public Formatter getFormatter() {
        return EmptyLanguage.EmptyFormatter.INSTANCE;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return null;
    }

    @Nullable
    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[0];
    }

    @Override
    public void destroy() {
        Disposer.dispose(disposable);
    }
}
