package com.tyron.code.ui.legacyEditor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.R;
import com.tyron.code.debug.PsiView;
import com.tyron.code.highlighter.JavaFileHighlighter;
import com.tyron.code.highlighter.SyntaxHighlighter;
import com.tyron.code.highlighter.attributes.CodeAssistTextAttributes;
import com.tyron.code.highlighter.attributes.TextAttributesKeyUtils;
import com.tyron.code.ui.main.TextEditorState;
import com.tyron.completion.CompletionInitializationContext;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProcess;
import com.tyron.completion.CompletionService;
import com.tyron.completion.CompletionType;
import com.tyron.completion.EditorMemory;
import com.tyron.completion.impl.CompletionInitializationUtil;
import com.tyron.completion.impl.OffsetsInFile;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.model.CompletionItemWithMatchLevel;
import com.tyron.completion.model.CompletionList;

import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageUtil;
import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.PlainTextLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.progress.EmptyProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.StandardProgressIndicatorBase;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;

import java.util.function.Supplier;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.DoubleClickEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub;

@SuppressLint("ViewConstructor")
public class EditorView extends FrameLayout {
    private final Project project;
    private final VirtualFile file;
    private final FileType fileType;

    private final CodeEditor editor;

    private final Document document;

    private final Disposable disposable;

    private ProgressIndicator completionProgressIndicator = new EmptyProgressIndicator();

    public EditorView(Context context, Project project, TextEditorState state) {
        super(context);

        this.project = project;
        this.file = state.getFile();
        this.fileType = state.getFileType();

        Language fileLanguage = LanguageUtil.getFileLanguage(this.file);
        if (fileLanguage == null) {
            fileLanguage = PlainTextLanguage.INSTANCE;
        }

        disposable = Disposer.newDisposable("fileEditor_" + file.getName());

        editor = new CodeEditor(context);
        editor.setColorScheme(new SchemeGitHub());
        editor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        editor.setTypefaceText(ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular));

        document = FileDocumentManager.getInstance().getDocument(file);

        EditorMemory.putUserData(editor, EditorMemory.DOCUMENT_KEY, document);

        PsiDocumentManagerBase documentManager =
                (PsiDocumentManagerBase) PsiDocumentManager.getInstance(project);
        PsiFile psiFile = documentManager.getPsiFile(document);
        EditorMemory.putUserData(editor, EditorMemory.FILE_KEY, psiFile);


        document.addDocumentListener(documentManager);
        document.addDocumentListener(documentManager.new PriorityEventCollector());
        editor.setText(document.getCharsSequence());


        editor.subscribeEvent(DoubleClickEvent.class, (event, unsubscribe) -> {
            PsiView psiView = new PsiView(getContext(), psiFile);
            new MaterialAlertDialogBuilder(getContext()).setView(psiView).show();
        });


        AnalyzeManager analyzeManager = new TestAnalyzeManager(new JavaFileHighlighter());
        editor.setEditorLanguage(new EmptyLanguage() {
            @NonNull
            @Override
            public AnalyzeManager getAnalyzeManager() {
                return analyzeManager;
            }

            @Override
            public void requireAutoComplete(@NonNull ContentReference content,
                                            @NonNull CharPosition position,
                                            @NonNull CompletionPublisher publisher,
                                            @NonNull Bundle extraArguments) {

                // there may be a race condition where the editor will call requireAutoComplete()
                // first before the text is synchronized to intellij, attempting to read the
                // psi will throw an error.
                if (!document.getText().contentEquals(content)) {
                    return;
                }

                completionProgressIndicator.cancel();
                completionProgressIndicator = new StandardProgressIndicatorBase();

                ProgressManager.getInstance().runProcess(() -> {
                    try {
                        EditorChangeUtil.performCompletionUnderIndicator(project, editor, publisher, disposable);
                    } catch (ProcessCanceledException e) {
                        // ignored, expected to be cancelled
                    }
                }, completionProgressIndicator);
            }
        });
        editor.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            commit(event.getAction(),
                    event.getChangeStart().index,
                    event.getChangeEnd().index,
                    event.getChangedText());
        });
        addView(editor);
    }

    private void commit(int action, int start, int end, CharSequence charSequence) {
        EditorChangeUtil.doCommit(action,
                start,
                end,
                charSequence,
                project, document);
    }

    public VirtualFile getFile() {
        return file;
    }

    public Project getProject() {
        return project;
    }

    public static class TestAnalyzeManager extends SimpleAnalyzeManager<Void> {

        private final SyntaxHighlighter highlighter;
        private ContentReference contentReference;

        public TestAnalyzeManager(SyntaxHighlighter highlighter) {
            this.highlighter = highlighter;
        }

        @Override
        public synchronized void rerun() {
            super.rerun();
        }

        @Override
        public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
            this.contentReference = content;
            super.reset(content, extraArguments);
        }

        @Override
        protected Styles analyze(StringBuilder text,
                                 SimpleAnalyzeManager<Void>.Delegate<Void> delegate) {
            Lexer lexer = highlighter.getHighlightingLexer();
            lexer.start(text);

            MappedSpans.Builder builder = new MappedSpans.Builder();
            while (!delegate.isCancelled()) {
                lexer.advance();

                IElementType tokenType = lexer.getTokenType();
                if (tokenType == null) {
                    break;
                }

                CharPosition tokenStart = contentReference.getCharPosition(lexer.getTokenStart());


                TextAttributesKey[] tokenHighlights = highlighter.getTokenHighlights(tokenType);

                if (tokenHighlights.length == 0) {
                    builder.add(tokenStart.getLine(),
                            Span.obtain(tokenStart.getColumn(), EditorColorScheme.TEXT_NORMAL));
                    continue;
                }

                TextAttributesKey first = tokenHighlights[0];
                CodeAssistTextAttributes defaultAttributes =
                        TextAttributesKeyUtils.getDefaultAttributes(first);

                if (defaultAttributes == null) {
                    builder.add(tokenStart.getLine(),
                            Span.obtain(tokenStart.getColumn(), EditorColorScheme.TEXT_NORMAL));
                    continue;
                }

                Span span;
                try {
                    span = Span.obtain(tokenStart.getColumn(),
                            TextStyle.makeStyle(defaultAttributes.getForegroundColor()));
                } catch (IllegalArgumentException e) {
                    span = Span.obtain(tokenStart.getColumn(),
                            TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL));
                }
                builder.add(tokenStart.getLine(), span);
            }

            return new Styles(builder.build());
        }
    }
}
