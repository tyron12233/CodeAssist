package com.tyron.code.ui.editor.action.text;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.actions.ActionGroup;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.Presentation;
import com.tyron.code.R;
import com.tyron.code.ui.editor.action.ExpandSelectionAction;

public class TextActionGroup extends ActionGroup {

    public static final String ID = "textActionGroup";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.text_actions));
    }

    @Override
    public boolean isPopup() {
        return true;
    }

    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
        return new AnAction[] {
                new ExpandSelectionAction(),
                new SelectAllAction(),
                new CutAction(),
                new CopyAction(),
                new PasteAction()
        };
    }
}
