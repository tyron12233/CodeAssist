package com.tyron.code.ui.legacyEditor.action.text;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.legacyEditor.Editor;

public class SelectAllAction extends CopyAction {

    @Override
    public void update(@NonNull AnActionEvent event) {
        super.update(event);

        if (event.getPresentation().isVisible()) {
            DataContext context = event.getDataContext();
            event.getPresentation().setText("Select all");
            event.getPresentation().setIcon(ResourcesCompat.getDrawable(context.getResources(),
                    io.github.rosemoe.sora2.R.drawable.round_select_all_20, context.getTheme()));
        }
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        editor.setSelectionRegion(0, editor.getContent().length());
    }
}
