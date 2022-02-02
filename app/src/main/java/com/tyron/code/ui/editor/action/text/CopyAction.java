package com.tyron.code.ui.editor.action.text;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

public class CopyAction extends AnAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        Caret caret = editor.getCaret();
        if (caret.getStartLine() == caret.getEndLine() &&
                caret.getStartColumn() == caret.getEndColumn()) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(io.github.rosemoe.sora2.R.string.copy));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Caret caret = editor.getCaret();
        CharSequence textToCopy = editor.getContent().subSequence(caret.getStart(),
                caret.getEnd());
        AndroidUtilities.copyToClipboard(textToCopy.toString(), true);
    }
}
