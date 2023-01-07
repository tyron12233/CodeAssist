package com.tyron.code.ui.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiJavaFileImpl;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentListener;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

@SuppressLint("ViewConstructor")
public class EditorView extends FrameLayout {

    private final ContentListener psiUpdater = new ContentListener() {
        @Override
        public void beforeReplace(Content content) {

        }

        @Override
        public void afterInsert(Content content,
                                int startLine,
                                int startColumn,
                                int endLine,
                                int endColumn,
                                CharSequence insertedContent) {
            int startIndex = content.getCharIndex(startLine, startColumn);
            int endIndex = content.getCharIndex(endLine, endColumn);
            commit(ContentChangeEvent.ACTION_INSERT, startIndex, endIndex, insertedContent);
        }

        @Override
        public void afterDelete(Content content,
                                int startLine,
                                int startColumn,
                                int endLine,
                                int endColumn,
                                CharSequence deletedContent) {
            int startIndex = content.getCharIndex(startLine, startColumn);
            int endIndex = content.getCharIndex(endLine, endColumn - deletedContent.length()) + deletedContent.length();
            commit(ContentChangeEvent.ACTION_DELETE, startIndex, endIndex, deletedContent);
        }

        private void commit(int action, int start, int end, CharSequence charSequence) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            assert psiFile != null;
            CommandProcessor.getInstance().executeCommand(project, () -> {
                Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
                assert document != null;
                if (action == ContentChangeEvent.ACTION_DELETE) {
                    document.deleteString(start, end);
                } else if (action == ContentChangeEvent.ACTION_INSERT) {
                    document.insertString(start, charSequence);
                }
                ((PsiJavaFileImpl) psiFile).onContentReload();
                PsiDocumentManager.getInstance(project).commitDocument(document);
            }, "", null);
        }
    };

    private final Project project;
    private final VirtualFile file;

    private final CodeEditor editor;

    public EditorView(Context context, Content content, Project project, VirtualFile file) {
        super(context);

        this.project = project;
        this.file = file;

        editor = new CodeEditor(context);
        editor.setText(content);

        content.addContentListener(psiUpdater);
        addView(editor);
    }
}
