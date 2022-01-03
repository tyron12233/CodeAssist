package com.flipkart.android.proteus;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Interface for changing the parser for a specific type of layout before inflating.
 * e.g converting a {@code Button} into {@code com.google.android.material.button.MaterialButton}
 * This is used to mimic the behavior of {@link android.view.LayoutInflater.Factory2}
 */
public interface ProteusParserFactory {

    @Nullable
    <T extends View> ViewTypeParser<T> getParser(@NonNull String type);
}
