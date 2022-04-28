package com.tyron.actions;

import android.content.Context;
import android.content.ContextWrapper;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;

public class DataContext extends ContextWrapper {

    private final UserDataHolderBase mUserDataHolder;

    public static DataContext wrap(Context context) {
        if (context instanceof DataContext) {
            return ((DataContext) context);
        }
        return new DataContext(context);
    }

    public DataContext(Context base) {
        super(base);

        mUserDataHolder = new UserDataHolderBase();
    }

    @Nullable
    public <T> T getData(@NotNull Key<T> key) {
        return mUserDataHolder.getUserData(key);
    }

    public <T> void putData(@NotNull Key<T> key, @Nullable T t) {
        mUserDataHolder.putUserData(key, t);
    }
}
