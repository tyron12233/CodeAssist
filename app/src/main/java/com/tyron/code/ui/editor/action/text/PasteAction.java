package com.tyron.code.ui.editor.action.text;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.Presentation;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

public class PasteAction extends CopyAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        super.update(event);

        Presentation presentation = event.getPresentation();
        if (!presentation.isVisible() && AndroidUtilities.getPrimaryClip() != null) {
            presentation.setVisible(true);
        }

        if (presentation.isVisible()) {
            DataContext context = event.getDataContext();
            presentation.setText(context.getString(io.github.rosemoe.sora2.R.string.paste));
            presentation.setIcon(ResourcesCompat.getDrawable(context.getResources(),
                    io.github.rosemoe.sora2.R.drawable.round_content_paste_20, context.getTheme()));
        }
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Caret caret = editor.getCaret();
        editor.replace(
                caret.getStartLine(),
                caret.getStartColumn(),
                caret.getEndLine(),
                caret.getEndColumn(),
                String.valueOf(AndroidUtilities.getPrimaryClip())
        );
    }
}
