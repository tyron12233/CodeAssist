package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.ForwardingListener;
import androidx.core.content.res.ResourcesCompat;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.builder.project.Project;
import com.tyron.code.R;
import com.tyron.code.language.LanguageManager;
import com.tyron.code.language.java.JavaLanguage;
import com.tyron.code.language.xml.LanguageXML;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.CoordinatePopupMenu;
import com.tyron.code.util.PopupMenuHelper;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.common.util.DebouncerStore;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.java.util.JavaDataContextUtil;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.task.InjectResourcesTask;
import com.tyron.editor.Content;
import com.tyron.editor.event.ContentEvent;
import com.tyron.editor.event.ContentListener;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.language.xml.XmlLanguage;
import com.tyron.viewbinding.task.InjectViewBindingTask;

import org.apache.commons.vfs2.FileObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.EditorKeyEvent;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer;
import io.github.rosemoe.sora.text.Cursor;
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

        content.addContentListener(new ContentListener() {
            @Override
            public void contentChanged(@NonNull ContentEvent event) {
                ProgressManager.getInstance().runNonCancelableAsync(() -> {
                    DebouncerStore.DEFAULT.registerOrGetDebouncer("contentChange").debounce(300, () -> {
                        try {
                            onContentChange(event.getContent());
                        } catch (Throwable t) {
                            LOGGER.error("Error in onContentChange", t);
                        }
                    });
                });
            }
        });
    }

    /**
     * Background updates
     *
     * @param content the current content when the update is called
     */
    private void onContentChange(Content content) throws IOException {
        Language language = editor.getEditorLanguage();
        Project project = editor.getProject();

        if (project == null) {
            return;
        }

        // TODO: Move implementation to each language
        if (language instanceof LanguageXML) {
            InjectResourcesTask.inject(project);
            InjectViewBindingTask.inject(project);
        } else if (language instanceof JavaLanguage) {

            CompilationInfo compilationInfo = CompilationInfo.get(project, editor.getCurrentFile());
            if (compilationInfo == null) {
                return;
            }
            JavaFileObject fileObject = new SimpleJavaFileObject(editor.getCurrentFile().toURI(),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return content;
                }
            };
            try {
                compilationInfo.update(fileObject);
            } catch (Throwable t) {
                LOGGER.error("Failed to update compilation unit", t);
            }
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
        editor.setDiagnostics(new DiagnosticsContainer());
        editor.setTypefaceText(
                ResourcesCompat.getFont(editor.getContext(), R.font.jetbrains_mono_regular));

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

            ProgressManager.getInstance().runLater(() -> {
                showPopupMenu(event);
            });
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
            JavaDataContextUtil.addEditorKeys(dataContext, currentProject, editor.getCurrentFile(),
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
