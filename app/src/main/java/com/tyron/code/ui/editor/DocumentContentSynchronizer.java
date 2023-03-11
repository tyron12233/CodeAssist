package com.tyron.code.ui.editor;

import androidx.annotation.NonNull;

import com.tyron.code.ui.legacyEditor.EditorChangeUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentEvent;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentListener;

/**
 * Synchronizes {@link Document} changes to the underlying {@link Content} and vice versa
 */
public class DocumentContentSynchronizer implements DocumentListener, ContentListener {

    private final Project project;
    private final Document document;
    private final Content content;

    public DocumentContentSynchronizer(Project project, Document document, Content content) {
        this.project = project;
        this.document = document;
        this.content = content;
    }

    public void start() {
        document.addDocumentListener(this);
        content.addContentListener(this);
    }

    public void stop() {
        document.removeDocumentListener(this);
        content.removeContentListener(this);
    }

    // document to content

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        content.removeContentListener(this);

        CharPosition position = content.getIndexer().getCharPosition(event.getOffset());
        if (event.getNewLength() > event.getOldLength()) {
            content.insert(position.line, position.column, event.getNewFragment());
        } else if (event.getOldLength() > event.getNewLength()) {
            content.delete(event.getOffset(), event.getOffset() + event.getOldFragment().length());
        } else {
            content.replace(event.getOffset(),
                    event.getOffset() + event.getOldFragment().length(),
                    event.getNewFragment());
        }

        content.addContentListener(this);
    }


    // content to document

    @Override
    public void afterInsert(@NonNull Content content,
                            int startLine,
                            int startColumn,
                            int endLine,
                            int endColumn,
                            @NonNull CharSequence insertedContent) {
        document.removeDocumentListener(this);

        int startIndex = content.getCharIndex(startLine, startColumn);
        int endIndex = content.getCharIndex(endLine, endColumn);
        EditorChangeUtil.doCommit(
                ContentChangeEvent.ACTION_INSERT,
                startIndex,
                endIndex,
                insertedContent,
                project,
                document
        );

        document.addDocumentListener(this);
    }

    @Override
    public void afterDelete(@NonNull Content content,
                            int startLine,
                            int startColumn,
                            int endLine,
                            int endColumn,
                            @NonNull CharSequence deletedContent) {
        document.removeDocumentListener(this);

        int startIndex = content.getCharIndex(startLine, startColumn);
        int endIndex = startIndex + deletedContent.length();
        EditorChangeUtil.doCommit(
                ContentChangeEvent.ACTION_DELETE,
                startIndex,
                endIndex,
                deletedContent,
                project,
                document
        );

        document.addDocumentListener(this);
    }

    @Override
    public void beforeModification(@NonNull Content content) {
        ContentListener.super.beforeModification(content);
    }

    @Override
    public void beforeReplace(@NonNull Content content) {

    }
}
