package com.tyron.code.ui.editor.impl.text.rosemoe.window;

import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;

import androidx.appcompat.view.menu.ListMenuPresenter;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.view.menu.MenuView;
import androidx.appcompat.widget.ActionMenuView;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.appcompat.widget.MenuPopupWindow;

import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.util.DataContextUtils;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;

@SuppressLint("RestrictedApi")
public class ActionsWindow extends EditorTextActionWindow {

    /**
     * Create a panel for the given editor
     *
     * @param editor Target editor
     */
    public ActionsWindow(CodeEditor editor) {
        super(editor);
    }

    @Override
    public void unregister() {
        super.unregister();
    }
}
