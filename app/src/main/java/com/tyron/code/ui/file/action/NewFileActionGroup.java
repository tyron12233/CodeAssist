package com.tyron.code.ui.file.action;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.actions.ActionGroup;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.code.R;
import com.tyron.code.ui.file.action.java.CreateClassAction;

public class NewFileActionGroup extends ActionGroup {

    @Override
    public void update(@NonNull AnActionEvent event) {
        event.getPresentation().setText(event.getDataContext().getString(R.string.menu_new));
    }

    @Override
    public AnAction[] getChildren(@Nullable AnActionEvent e) {
        return new AnAction[]{new CreateClassAction()};
    }
}
