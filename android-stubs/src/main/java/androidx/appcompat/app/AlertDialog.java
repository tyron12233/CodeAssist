package androidx.appcompat.app;

import android.content.DialogInterface;
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
import androidx.annotation.StringRes;

import androidx.appcompat.app.AlertDialog;

public class AlertDialog {
    public void dismiss() {
        throw new RuntimeException("Stub!");
    }

    public static class Builder {
        @NonNull
        public AlertDialog create() {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setTitle(@StringRes int titleId) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setTitle(@Nullable CharSequence title) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setCustomTitle(@Nullable View customTitleView) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setMessage(@StringRes int messageId) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setMessage(@Nullable CharSequence message) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setIcon(@DrawableRes int iconId) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setIcon(@Nullable Drawable icon) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setIconAttribute(@AttrRes int attrId) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setPositiveButton(
                @StringRes int textId, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setPositiveButton(
                @Nullable CharSequence text, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setPositiveButtonIcon(@Nullable Drawable icon) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setNegativeButton(
                @StringRes int textId, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setNegativeButton(
                @Nullable CharSequence text, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setNegativeButtonIcon(@Nullable Drawable icon) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setNeutralButton(
                @StringRes int textId, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setNeutralButton(
                @Nullable CharSequence text, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setNeutralButtonIcon(@Nullable Drawable icon) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setCancelable(boolean cancelable) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setOnCancelListener(
                @Nullable DialogInterface.OnCancelListener onCancelListener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setOnDismissListener(
                @Nullable DialogInterface.OnDismissListener onDismissListener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setOnKeyListener(@Nullable DialogInterface.OnKeyListener onKeyListener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setItems(
                @ArrayRes int itemsId, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setItems(
                @Nullable CharSequence[] items, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setAdapter(
                @Nullable final ListAdapter adapter, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setCursor(
                @Nullable final Cursor cursor,
                @Nullable final DialogInterface.OnClickListener listener,
                @NonNull String labelColumn) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setMultiChoiceItems(
                @ArrayRes int itemsId,
                @Nullable boolean[] checkedItems,
                @Nullable final DialogInterface.OnMultiChoiceClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setMultiChoiceItems(
                @Nullable CharSequence[] items,
                @Nullable boolean[] checkedItems,
                @Nullable final DialogInterface.OnMultiChoiceClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setMultiChoiceItems(
                @Nullable Cursor cursor,
                @NonNull String isCheckedColumn,
                @NonNull String labelColumn,
                @Nullable final DialogInterface.OnMultiChoiceClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setSingleChoiceItems(
                @ArrayRes int itemsId, int checkedItem, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setSingleChoiceItems(
                @Nullable Cursor cursor,
                int checkedItem,
                @NonNull String labelColumn,
                @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setSingleChoiceItems(
                @Nullable CharSequence[] items, int checkedItem, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setSingleChoiceItems(
                @Nullable ListAdapter adapter, int checkedItem, @Nullable final DialogInterface.OnClickListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setOnItemSelectedListener(
                @Nullable final AdapterView.OnItemSelectedListener listener) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setView(int layoutResId) {
            throw new RuntimeException("Stub!");
        }

        @NonNull
        public AlertDialog.Builder setView(@Nullable View view) {
            throw new RuntimeException("Stub!");
        }

        public AlertDialog show() {
            throw new RuntimeException("Stub!");
        }
    }
}
