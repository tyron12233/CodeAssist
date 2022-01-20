package com.tyron.actions;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;

public interface ActionPopupMenu {

    @NonNull
    PopupMenu getComponent();

    @NonNull
    String getPlace();

    @NonNull
    ActionGroup getActionGroup();

    void setTargetComponent(@NonNull View view);
}
