package com.tyron.actions;

import androidx.annotation.NonNull;

public class AnActionEvent implements PlaceProvider {

    private final String mPlace;
    private final Presentation mPresentation;
    private final boolean mIsContextMenuAction;
    private final boolean mIsActionToolbar;

    public AnActionEvent(@NonNull String place,
                         @NonNull Presentation presentation,
                         boolean isContextMenuAction,
                         boolean isActionToolbar) {
        mPlace = place;
        mPresentation = presentation;
        mIsContextMenuAction = isContextMenuAction;
        mIsActionToolbar = isActionToolbar;
    }

    @Override
    public String getPlace() {
        return mPlace;
    }

    public Presentation getPresentation() {
        return mPresentation;
    }

    public boolean isContextMenuAction() {
        return mIsContextMenuAction;
    }

    public boolean isActionToolbar() {
        return mIsActionToolbar;
    }
}
