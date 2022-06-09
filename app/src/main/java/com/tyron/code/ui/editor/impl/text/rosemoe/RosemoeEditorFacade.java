package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
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
import com.tyron.builder.model.DiagnosticWrapper;
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
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.completion.java.util.JavaDataContextUtil;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Content;
import com.tyron.fileeditor.api.FileEditor;

import org.apache.commons.vfs2.FileObject;

import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.LongPressEvent;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora2.text.EditorUtil;

public class RosemoeEditorFacade {

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
        editor.setTypefaceText(ResourcesCompat.getFont(editor.getContext(), R.font.jetbrains_mono_regular));

        editor.setAutoCompletionItemAdapter(new CodeAssistCompletionAdapter());
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setInputType(EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            | EditorInfo.TYPE_CLASS_TEXT
                            | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                            | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

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

        DiagnosticWrapper diagnosticWrapper = DiagnosticUtil
                .getDiagnosticWrapper(editor.getDiagnostics(),
                        editor.getCursor().getLeft(),
                        editor.getCursor().getRight());
        if (diagnosticWrapper == null && editor.getEditorLanguage() instanceof LanguageXML) {
            diagnosticWrapper = DiagnosticUtil.getXmlDiagnosticWrapper(editor.getDiagnostics(),
                    editor.getCursor()
                            .getLeftLine());
        }
        dataContext.putData(CommonDataKeys.DIAGNOSTIC, diagnosticWrapper);
        return dataContext;
    }

    public View getView() {
        return container;
    }

    public Content getContent() {
        return content;
    }
}
