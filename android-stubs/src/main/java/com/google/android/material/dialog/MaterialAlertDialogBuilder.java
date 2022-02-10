/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.material.dialog;

import android.content.Context;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.DialogInterface.OnKeyListener;
import android.content.DialogInterface.OnMultiChoiceClickListener;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListAdapter;

import androidx.annotation.ArrayRes;
import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Px;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

/**
 * An extension of {@link AlertDialog.Builder} for use with a Material theme (e.g.,
 * Theme.MaterialComponents).
 *
 * <p>This Builder must be used in order for AlertDialog objects to respond to color and shape
 * theming provided by Material themes.
 *
 * <p>The type of dialog returned is still an {@link AlertDialog}; there is no specific Material
 * implementation of {@link AlertDialog}.
 */
public class MaterialAlertDialogBuilder extends AlertDialog.Builder {

    public MaterialAlertDialogBuilder(@NonNull Context context) {
        throw new RuntimeException("Stub!");
    }

    public MaterialAlertDialogBuilder(@NonNull Context context, int overrideThemeResId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public AlertDialog create() {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    public Drawable getBackground() {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public MaterialAlertDialogBuilder setBackground(@Nullable Drawable background) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public MaterialAlertDialogBuilder setBackgroundInsetStart(@Px int backgroundInsetStart) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public MaterialAlertDialogBuilder setBackgroundInsetTop(@Px int backgroundInsetTop) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public MaterialAlertDialogBuilder setBackgroundInsetEnd(@Px int backgroundInsetEnd) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public MaterialAlertDialogBuilder setBackgroundInsetBottom(@Px int backgroundInsetBottom) {
        throw new RuntimeException("Stub!");
    }

    // The following methods are all pass-through methods used to specify the return type for the
    // builder chain.

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setTitle(@StringRes int titleId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setTitle(@Nullable CharSequence title) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setCustomTitle(@Nullable View customTitleView) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setMessage(@StringRes int messageId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setMessage(@Nullable CharSequence message) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setIcon(@DrawableRes int iconId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setIcon(@Nullable Drawable icon) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setIconAttribute(@AttrRes int attrId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setPositiveButton(
            @StringRes int textId, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setPositiveButton(
            @Nullable CharSequence text, @Nullable final OnClickListener listener) {
        return (MaterialAlertDialogBuilder) super.setPositiveButton(text, listener);
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setPositiveButtonIcon(@Nullable Drawable icon) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setNegativeButton(
            @StringRes int textId, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setNegativeButton(
            @Nullable CharSequence text, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setNegativeButtonIcon(@Nullable Drawable icon) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setNeutralButton(
            @StringRes int textId, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setNeutralButton(
            @Nullable CharSequence text, @Nullable final OnClickListener listener) {
        return (MaterialAlertDialogBuilder) super.setNeutralButton(text, listener);
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setNeutralButtonIcon(@Nullable Drawable icon) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setCancelable(boolean cancelable) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setOnCancelListener(
            @Nullable OnCancelListener onCancelListener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setOnDismissListener(
            @Nullable OnDismissListener onDismissListener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setOnKeyListener(@Nullable OnKeyListener onKeyListener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setItems(
            @ArrayRes int itemsId, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setItems(
            @Nullable CharSequence[] items, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setAdapter(
            @Nullable final ListAdapter adapter, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setCursor(
            @Nullable final Cursor cursor,
            @Nullable final OnClickListener listener,
            @NonNull String labelColumn) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setMultiChoiceItems(
            @ArrayRes int itemsId,
            @Nullable boolean[] checkedItems,
            @Nullable final OnMultiChoiceClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setMultiChoiceItems(
            @Nullable CharSequence[] items,
            @Nullable boolean[] checkedItems,
            @Nullable final OnMultiChoiceClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setMultiChoiceItems(
            @Nullable Cursor cursor,
            @NonNull String isCheckedColumn,
            @NonNull String labelColumn,
            @Nullable final OnMultiChoiceClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setSingleChoiceItems(
            @ArrayRes int itemsId, int checkedItem, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setSingleChoiceItems(
            @Nullable Cursor cursor,
            int checkedItem,
            @NonNull String labelColumn,
            @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setSingleChoiceItems(
            @Nullable CharSequence[] items, int checkedItem, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setSingleChoiceItems(
            @Nullable ListAdapter adapter, int checkedItem, @Nullable final OnClickListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setOnItemSelectedListener(
            @Nullable final AdapterView.OnItemSelectedListener listener) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setView(int layoutResId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    @Override
    public MaterialAlertDialogBuilder setView(@Nullable View view) {
        throw new RuntimeException("Stub!");
    }
}
