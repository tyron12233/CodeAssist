package com.tyron.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.beans.PropertyChangeSupport;

public abstract class ActionGroup extends AnAction {

    private final PropertyChangeSupport mChangeSupport = new PropertyChangeSupport(this);

    public static final ActionGroup EMPTY_GROUP = new ActionGroup() {

        @Override
        public AnAction[] getChildren(@Nullable AnActionEvent e) {
            return new AnAction[0];
        }
    };

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {

    }

    public boolean isPopup() {
        return true;
    }

    public abstract AnAction[] getChildren(@Nullable AnActionEvent e);

}
