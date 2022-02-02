package com.tyron.code.ui.editor.action.text;

import androidx.annotation.NonNull;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

public class CutAction extends CopyAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        super.update(event);

        if (event.getPresentation().isVisible()) {
            event.getPresentation().setText("Cut");
        }
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Caret caret = editor.getCaret();
        int startIndex = caret.getStart();
        int endIndex = caret.getEnd();
        super.actionPerformed(e);

        editor.delete(startIndex, endIndex);
    }
}
