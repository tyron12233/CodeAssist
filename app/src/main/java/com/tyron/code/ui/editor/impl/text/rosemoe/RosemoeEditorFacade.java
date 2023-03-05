package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.appcompat.widget.ForwardingListener;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.event.EventManager;
import com.tyron.code.event.PerformShortcutEvent;
import com.tyron.code.language.LanguageManager;
import com.tyron.code.language.java.JavaLanguage;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.code.ui.editor.CodeAssistCompletionWindow;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.CoordinatePopupMenu;
import com.tyron.code.util.PopupMenuHelper;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.common.util.DebouncerStore;
import com.tyron.completion.java.util.JavaDataContextUtil;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.diagnostics.DiagnosticProvider;
import com.tyron.editor.Content;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.language.api.CodeAssistLanguage;

import org.apache.commons.vfs2.FileObject;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Function;

import javax.tools.Diagnostic;

import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.EditorKeyEvent;
import io.github.rosemoe.sora.event.Event;
import io.github.rosemoe.sora.event.InterceptTarget;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora2.text.EditorUtil;

public class RosemoeEditorFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(RosemoeEditorFacade.class);

    private final FileEditor fileEditor;
    private final Content content;
    private final FrameLayout container;
    private final CodeEditorView editor;

    private View.OnTouchListener dragToOpenListener;

    RosemoeEditorFacade(RosemoeCodeEditor rosemoeCodeEditor,
                        Context context,
                        Content content,
                        FileObject file) {
        this.fileEditor = rosemoeCodeEditor;
        this.content = content;

        container = new FrameLayout(context);

        editor = new CodeEditorView(context);
        configureEditor(editor, file);
        container.addView(editor);


        EventManager eventManager = ApplicationLoader.getInstance().getEventManager();
        eventManager.subscribeEvent(PerformShortcutEvent.class, (event, unsubscribe) -> {
            if (event.getEditor() == rosemoeCodeEditor) {
                event.getItem().actions.forEach(it -> it.apply(editor, event.getItem()));
            }
        });
    }

    /**
     * Background updates
     *
     * @param content the current content when the update is called
     */
    private void onContentChange(Content content) {
        Language language = editor.getEditorLanguage();
        File currentFile = editor.getCurrentFile();
        Project project = editor.getProject();
        if (project == null) {
            return;
        }
        Module module = project.getModule(currentFile);
        if (module == null) {
            return;
        }

        if (language instanceof CodeAssistLanguage) {
            ((CodeAssistLanguage) language).onContentChange(currentFile, content);
        }

        Objects.requireNonNull(editor.getDiagnostics()).reset();

        ServiceLoader<DiagnosticProvider> providers = ServiceLoader.load(DiagnosticProvider.class);
        for (DiagnosticProvider provider : providers) {
            List<? extends Diagnostic<?>> diagnostics =
                    provider.getDiagnostics(module, currentFile);
            Function<Diagnostic.Kind, Short> severitySupplier = it -> {
                switch (it) {
                    case ERROR:
                        return DiagnosticRegion.SEVERITY_ERROR;
                    case MANDATORY_WARNING:
                    case WARNING:
                        return DiagnosticRegion.SEVERITY_WARNING;
                    default:
                    case OTHER:
                    case NOTE:
                        return DiagnosticRegion.SEVERITY_NONE;
                }
            };
            diagnostics.stream()
                    .map(it -> new DiagnosticRegion((int) it.getStartPosition(),
                            (int) it.getEndPosition(),
                            severitySupplier.apply(it.getKind())))
                    .forEach(Objects.requireNonNull(editor.getDiagnostics())::addDiagnostic);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configureEditor(CodeEditorView editor, FileObject file) {
        Language language = LanguageManager.getInstance().get(editor, file);
        if (language == null) {
            language = new EmptyLanguage();
        }
        editor.setEditorLanguage(language);

        // only used for checking the extension
        editor.openFile(file.getPath().toFile());
        Bundle bundle = new Bundle();
        bundle.putBoolean("loaded", true);
        bundle.putBoolean("bg", true);
        editor.setText(content, bundle);
        editor.setHighlightBracketPair(false);
        editor.setDiagnostics(new DiagnosticsContainer());
        editor.setTypefaceText(ResourcesCompat.getFont(editor.getContext(),
                R.font.jetbrains_mono_regular));

        editor.replaceComponent(EditorAutoCompletion.class, new CodeAssistCompletionWindow(editor));
        editor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                            EditorInfo.TYPE_CLASS_TEXT |
                            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE |
                            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        editor.setOnTouchListener((v, motionEvent) -> {
            if (dragToOpenListener instanceof ForwardingListener) {
                PopupMenuHelper.setForwarding((ForwardingListener) dragToOpenListener);
                // noinspection RestrictedApi
                dragToOpenListener.onTouch(v, motionEvent);
            }
            return false;
        });
        editor.subscribeEvent(LongPressEvent.class, (event, unsubscribe) -> {
            event.intercept();

            Cursor cursor = editor.getCursor();
            if (cursor.isSelected()) {
                int index = editor.getCharIndex(event.getLine(), event.getColumn());
                int cursorLeft = cursor.getLeft();
                int cursorRight = cursor.getRight();
                char c = editor.getText().charAt(index);
                if (Character.isWhitespace(c)) {
                    editor.setSelection(event.getLine(), event.getColumn());
                } else if (index < cursorLeft || index > cursorRight) {
                    EditorUtil.selectWord(editor, event.getLine(), event.getColumn());
                }
            } else {
                char c = editor.getText().charAt(event.getIndex());
                if (!Character.isWhitespace(c)) {
                    EditorUtil.selectWord(editor, event.getLine(), event.getColumn());
                } else {
                    editor.setSelection(event.getLine(), event.getColumn());
                }
            }

            ProgressManager.getInstance().runLater(() -> showPopupMenu(event));
        });
        editor.subscribeEvent(ClickEvent.class, (event, unsubscribe) -> {
            Cursor cursor = editor.getCursor();
            if (editor.getCursor().isSelected()) {
                int index = editor.getCharIndex(event.getLine(), event.getColumn());
                int cursorLeft = cursor.getLeft();
                int cursorRight = cursor.getRight();
                if (!EditorUtil.isWhitespace(editor.getText().charAt(index) + "") &&
                    index >= cursorLeft &&
                    index <= cursorRight) {
                    editor.showSoftInput();
                    event.intercept();
                }
            }
        });

        editor.subscribeEvent(EditorKeyEvent.class, (event, unsubscribe) -> {
            CodeAssistCompletionWindow window =
                    (CodeAssistCompletionWindow) editor.getComponent(EditorAutoCompletion.class);
            if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER ||
                event.getKeyCode() == KeyEvent.KEYCODE_TAB) {
                if (window.isShowing() && window.trySelect()) {
                    event.setResult(true);

                    // KeyEvent cannot be intercepted???
                    // workaround
                    Field mInterceptTargets =
                            ReflectionUtil.getDeclaredField(Event.class, "mInterceptTargets");
                    mInterceptTargets.setAccessible(true);
                    try {
                        mInterceptTargets.set(event, InterceptTarget.TARGET_EDITOR);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("REFLECTION FAILED");
                    }

                    editor.requestFocus();
                }
            }
        });
        editor.subscribeEvent(ContentChangeEvent.class,
                (event, unsubscribe) -> ProgressManager.getInstance()
                        .runNonCancelableAsync(() -> DebouncerStore.DEFAULT.registerOrGetDebouncer(
                                "contentChange").debounce(300, () -> {
                            try {
                                onContentChange(editor.getContent());
                            } catch (Throwable t) {
                                LOGGER.error("Error in onContentChange", t);
                            }
                        })));
    }

    /**
     * Show the popup menu with the actions api
     */
    private void showPopupMenu(LongPressEvent event) {
        MotionEvent e = event.getCausingEvent();
        CoordinatePopupMenu popupMenu =
                new CoordinatePopupMenu(editor.getContext(), editor, Gravity.BOTTOM);
        DataContext dataContext = createDataContext();
        ActionManager.getInstance()
                .fillMenu(dataContext, popupMenu.getMenu(), ActionPlaces.EDITOR, true, false);
        popupMenu.show((int) e.getX(), ((int) e.getY()) - AndroidUtilities.dp(24));

        // we don't want to enable the drag to open listener right away,
        // this may cause the buttons to be clicked right away
        // so wait for a few ms
        ProgressManager.getInstance().runLater(() -> {
            popupMenu.setOnDismissListener(d -> dragToOpenListener = null);
            dragToOpenListener = popupMenu.getDragToOpenListener();
        }, 300);
    }

    /**
     * Create the data context specific to this fragment for use with the actions API.
     *
     * @return the data context.
     */
    private DataContext createDataContext() {
        Project currentProject = ProjectManager.getInstance().getCurrentProject();

        DataContext dataContext = DataContextUtils.getDataContext(editor);
        dataContext.putData(CommonDataKeys.PROJECT, currentProject);
        dataContext.putData(CommonDataKeys.FILE_EDITOR_KEY, fileEditor);
        dataContext.putData(CommonDataKeys.FILE, editor.getCurrentFile());
        dataContext.putData(CommonDataKeys.EDITOR, editor);

        if (currentProject != null && editor.getEditorLanguage() instanceof JavaLanguage) {
            JavaDataContextUtil.addEditorKeys(dataContext,
                    currentProject,
                    editor.getCurrentFile(),
                    editor.getCursor().getLeft());
        }
        return dataContext;
    }

    public View getView() {
        return container;
    }

    public Content getContent() {
        return content;
    }
}
