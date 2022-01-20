package com.tyron.actions;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

public class AnActionEvent implements PlaceProvider {

    private final DataContext mDataContext;
    private final String mPlace;
    private Presentation mPresentation;
    private final boolean mIsContextMenuAction;
    private final boolean mIsActionToolbar;

    public AnActionEvent(@NonNull DataContext context,
                         @NonNull String place,
                         @NonNull Presentation presentation,
                         boolean isContextMenuAction,
                         boolean isActionToolbar) {
        mDataContext = context;
        mPlace = place;
        mPresentation = presentation;
        mIsContextMenuAction = isContextMenuAction;
        mIsActionToolbar = isActionToolbar;
    }

    public void setPresentation(Presentation presentation) {
        mPresentation = presentation;
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

    public <T> T getData(Key<T> key) {
        return mDataContext.getData(key);
    }

    public DataContext getDataContext() {
        return mDataContext;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public <T> void injectData(Key<T> key, T value) {
        mDataContext.putData(key, value);
    }
}
