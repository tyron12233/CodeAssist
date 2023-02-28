package com.tyron.code.ui.legacyEditor.action.text;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.legacyEditor.Caret;
import com.tyron.legacyEditor.Editor;

public class CutAction extends CopyAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        super.update(event);

        if (event.getPresentation().isVisible()) {
            DataContext context = event.getDataContext();
            event.getPresentation().setText("Cut");
            event.getPresentation().setIcon(ResourcesCompat.getDrawable(context.getResources(),
                    io.github.rosemoe.sora2.R.drawable.round_content_cut_20, context.getTheme()));
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
