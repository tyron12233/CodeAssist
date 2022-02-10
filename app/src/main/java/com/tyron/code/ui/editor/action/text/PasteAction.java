package com.tyron.code.ui.editor.action.text;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.actions.Presentation;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

import java.util.Arrays;
import java.util.stream.Collectors;

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

        if (caret.isSelected()) {
            editor.delete(caret.getStart(), caret.getEnd());
        }

        String clip = String.valueOf(AndroidUtilities.getPrimaryClip());
        String[] lines = clip.split("\n");
        if (lines.length == 0) {
            lines = new String[]{clip};
        }

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            lines[i] = trimmed;
        }

        String textToCopy = String.join("\n", lines);
        editor.insertMultilineString(
                caret.getStartLine(),
                caret.getStartColumn(),
                textToCopy
        );
    }
}
