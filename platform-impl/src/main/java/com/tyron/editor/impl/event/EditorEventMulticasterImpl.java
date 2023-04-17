package com.tyron.editor.impl.event;

import com.tyron.editor.event.EditorEventMulticaster;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentEvent;
import org.jetbrains.kotlin.com.intellij.openapi.editor.event.DocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.PrioritizedDocumentListener;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.EditorDocumentPriorities;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.util.EventDispatcher;

import java.util.Collections;

public class EditorEventMulticasterImpl implements EditorEventMulticaster {
    private static final ExtensionPointName<DocumentListener> DOCUMENT_EP = new ExtensionPointName<>("com.intellij.editorFactoryDocumentListener");

    private final EventDispatcher<DocumentListener> myDocumentMulticaster = EventDispatcher.create(
            DocumentListener.class);
    private final EventDispatcher<PrioritizedDocumentListener> myPrioritizedDocumentMulticaster = EventDispatcher.create(
            PrioritizedDocumentListener.class);

    public void registerDocument(@NotNull DocumentEx document) {
        document.addDocumentListener(myDocumentMulticaster.getMulticaster());
        document.addDocumentListener(new DocumentListener() {
            @Override
            public void beforeDocumentChange(@NotNull DocumentEvent event) {
                DOCUMENT_EP.forEachExtensionSafe(it -> it.beforeDocumentChange(event));
            }

            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                DOCUMENT_EP.forEachExtensionSafe(it -> it.documentChanged(event));
            }

//            @Override
//            public void bulkUpdateStarting(@NotNull Document document) {
//                DOCUMENT_EP.forEachExtensionSafe(it -> it.bulkUpdateStarting(document));
//            }
//
//            @Override
//            public void bulkUpdateFinished(@NotNull Document document) {
//                DOCUMENT_EP.forEachExtensionSafe(it -> it.bulkUpdateFinished(document));
//            }
        });
//        document.addDocumentListener(myPrioritizedDocumentMulticaster.getMulticaster());
//        document.addEditReadOnlyListener(myEditReadOnlyMulticaster.getMulticaster());
    }

    @Override
    public void addDocumentListener(@NotNull DocumentListener listener) {
        myDocumentMulticaster.addListener(listener);
    }

    @Override
    public void addDocumentListener(@NotNull DocumentListener listener, @NotNull Disposable parentDisposable) {
        myDocumentMulticaster.addListener(listener);
        Disposer.register(parentDisposable, () -> myDocumentMulticaster.removeListener(listener));
    }

    @Override
    public void removeDocumentListener(@NotNull DocumentListener listener) {
        myDocumentMulticaster.removeListener(listener);
    }
}
