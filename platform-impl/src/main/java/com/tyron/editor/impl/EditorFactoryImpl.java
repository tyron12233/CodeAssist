package com.tyron.editor.impl;

import com.tyron.editor.Editor;
import com.tyron.editor.EditorFactory;
import com.tyron.editor.EditorKind;
import com.tyron.editor.event.EditorEventMulticaster;
import com.tyron.editor.event.EditorFactoryEvent;
import com.tyron.editor.event.EditorFactoryListener;
import com.tyron.editor.ex.EditorEx;
import com.tyron.editor.impl.event.EditorEventMulticasterImpl;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.injected.editor.DocumentWindow;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.EventDispatcher;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.kotlin.com.intellij.util.text.CharArrayCharSequence;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public class EditorFactoryImpl extends EditorFactory {

    private final Set<Editor> editors = new HashSet<>();

    private static final ExtensionPointName<EditorFactoryListener> EP = new ExtensionPointName<>("com.intellij.editorFactoryListener");

    private static final Logger LOG = Logger.getInstance(EditorFactoryImpl.class);
    private final EditorEventMulticasterImpl myEditorEventMulticaster = new EditorEventMulticasterImpl();
    private final EventDispatcher<EditorFactoryListener> myEditorFactoryEventDispatcher = EventDispatcher.create(
            EditorFactoryListener.class);

    public EditorFactoryImpl() {
        MessageBusConnection busConnection =
                ApplicationManager.getApplication().getMessageBus().connect();
    }

    public void validateEditorsAreReleased(@NotNull Project project, boolean isLastProjectClosed) {
        collectAllEditors().forEach(editor -> {
            if (editor.getProject() == project || (editor.getProject() == null && isLastProjectClosed)) {
                try {
                    throwNotReleasedError(editor);
                }
                finally {
                    releaseEditor(editor);
                }
            }
        });
    }

    @NonNls
    public static void throwNotReleasedError(@NotNull Editor editor) {
        if (editor instanceof EditorImpl) {
            ((EditorImpl)editor).throwDisposalError("Editor " + editor + " hasn't been released:");
        }
        throw new RuntimeException("Editor of " + editor.getClass() +
                                   " and the following text hasn't been released:\n" + editor.getDocument().getText());
    }

    @Override
    public void refreshAllEditors() {
        collectAllEditors().forEach(editor -> {
            ((EditorEx)editor).reinitSettings();
        });
    }

    @Override
    public Editor createEditor(@NotNull Document document) {
        return createEditor(document, false, null, EditorKind.UNTYPED);
    }

    @Override
    public Editor createViewer(@NotNull Document document) {
        return createEditor(document, true, null, EditorKind.UNTYPED);
    }

    @Override
    public Editor createEditor(@NotNull Document document, Project project) {
        return createEditor(document, false, project, EditorKind.UNTYPED);
    }

    @Override
    public Editor createEditor(@NotNull Document document, @Nullable Project project, @NotNull EditorKind kind) {
        return createEditor(document, false, project, kind);
    }

    @Override
    public Editor createViewer(@NotNull Document document, Project project) {
        return createEditor(document, true, project, EditorKind.UNTYPED);
    }

    @Override
    public Editor createViewer(@NotNull Document document, @Nullable Project project, @NotNull EditorKind kind) {
        return createEditor(document, true, project, kind);
    }

    @Override
    public Editor createEditor(final @NotNull Document document, final Project project, final @NotNull FileType fileType, final boolean isViewer) {
        EditorEx editor = createEditor(document, isViewer, project, EditorKind.UNTYPED);
//        editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType));
        return editor;
    }

    @Override
    public Editor createEditor(@NotNull Document document, Project project, @NotNull VirtualFile file, boolean isViewer) {
        EditorEx editor = createEditor(document, isViewer, project, EditorKind.UNTYPED);
//        editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file));
        return editor;
    }

    @Override
    public Editor createEditor(@NotNull Document document,
                               Project project,
                               @NotNull VirtualFile file,
                               boolean isViewer,
                               @NotNull EditorKind kind) {
        EditorEx editor = createEditor(document, isViewer, project, kind);
//        editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, file));
        return editor;
    }

    private @NotNull EditorImpl createEditor(@NotNull Document document, boolean isViewer, Project project, @NotNull EditorKind kind) {
        Document hostDocument = document instanceof DocumentWindow ? ((DocumentWindow)document).getDelegate() : document;
        EditorImpl editor = new EditorImpl(hostDocument, isViewer, project, kind, null);
//        ClientEditorManager editorManager = ClientEditorManager.getCurrentInstance();
        postEditorCreation(editor);
        return editor;
    }

    private void postEditorCreation(EditorImpl editor) {
        EditorFactoryEvent event = new EditorFactoryEvent(this, editor);
        myEditorFactoryEventDispatcher.getMulticaster().editorCreated(event);
        EP.forEachExtensionSafe(it -> it.editorCreated(event));

        editors.add(editor);
    }

    @Override
    public @NotNull Document createDocument(char @NotNull [] text) {
        return createDocument(new CharArrayCharSequence(text));
    }

    @Override
    public @NotNull Document createDocument(@NotNull CharSequence text) {
        DocumentEx document = new DocumentImpl(text);
        myEditorEventMulticaster.registerDocument(document);
        return document;
    }

    public @NotNull Document createDocument(boolean allowUpdatesWithoutWriteAction) {
        DocumentEx document = new DocumentImpl("", allowUpdatesWithoutWriteAction);
        myEditorEventMulticaster.registerDocument(document);
        return document;
    }

    public @NotNull Document createDocument(@NotNull CharSequence text, boolean acceptsSlashR, boolean allowUpdatesWithoutWriteAction) {
        DocumentEx document = new DocumentImpl(text, acceptsSlashR, allowUpdatesWithoutWriteAction);
        myEditorEventMulticaster.registerDocument(document);
        return document;
    }

    @Override
    public void releaseEditor(@NotNull Editor editor) {
        assert ApplicationManager.getApplication().isDispatchThread();
        try {
            EditorFactoryEvent event = new EditorFactoryEvent(this, editor);
            myEditorFactoryEventDispatcher.getMulticaster().editorReleased(event);
            EP.forEachExtensionSafe(it -> it.editorReleased(event));
        }
        finally {
            if (editor instanceof EditorImpl) {
                ((EditorImpl)editor).release();
            }

            editors.remove(editor);
        }
    }


    @Override
    public @NotNull Stream<Editor> editors(@NotNull Document document, @Nullable Project project) {
        return collectAllEditors()
                .filter(editor -> editor.getDocument().equals(document) && (project == null || project.equals(editor.getProject())));
    }

    private @NotNull Stream<Editor> collectAllEditors() {
        return editors.stream();
    }

    @Override
    public Editor @NotNull [] getAllEditors() {
        return collectAllEditors().toArray(Editor[]::new);
    }

    @Override
    public void addEditorFactoryListener(@NotNull EditorFactoryListener listener) {
        myEditorFactoryEventDispatcher.addListener(listener);
    }

    @Override
    public void addEditorFactoryListener(@NotNull EditorFactoryListener listener,
                                         @NotNull Disposable parentDisposable) {
        myEditorFactoryEventDispatcher.addListener(listener);
        Disposer.register(parentDisposable, () -> myEditorFactoryEventDispatcher.removeListener(listener));
    }

    @Override
    public void removeEditorFactoryListener(@NotNull EditorFactoryListener listener) {
        myEditorFactoryEventDispatcher.removeListener(listener);
    }

    @Override
    public @NotNull EditorEventMulticaster getEventMulticaster() {
        return myEditorEventMulticaster;
    }
}
