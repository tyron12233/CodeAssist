package com.tyron.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

/**
 * Container for information necessary to execute or update an {@link AnAction}
 *
 * @see AnAction#update(AnActionEvent)
 * @see AnAction#actionPerformed(AnActionEvent)
 */
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

    @Nullable
    public <T> T getData(Key<T> key) {
        return mDataContext.getData(key);
    }

    /**
     * Returns a non null data by a data key. This method assumes that data has been checked
     * for {@code null} in {@code AnAction#update} method.
     *
     * <br/><br/>
     * Example of proper usage:
     *
     * <pre>
     *     public class MyAction extends AnAction {
     *         public void update(AnActionEvent e) {
     *             // perform action if and only if EDITOR != null
     *             boolean visible = e.getData(CommonDataKeys.EDITOR) != null;
     *             e.getPresentation().setVisible(visible);
     *         }
     *
     *         public void actionPerformed(AnActionEvent e) {
     *             // if we're here then EDITOR != null
     *             Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
     *         }
     *     }
     * </pre>
     */
    @NonNull
    public <T> T getRequiredData(Key<T> key) {
        T data = getData(key);
        assert data != null;
        return data;
    }

    /**
     * Returns the data context which allows to retrieve information about the state of the IDE
     * related to the action invocation. (Active editor, fragment and so on)
     *
     * @return the data context instance
     */
    public DataContext getDataContext() {
        return mDataContext;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public <T> void injectData(Key<T> key, T value) {
        mDataContext.putData(key, value);
    }
}
