package com.tyron.code.util;

import android.content.Context;
import android.util.AttributeSet;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

/**
 * Used in situations where the layout is needed to resize in response to an input method while
 * still being able to draw behind status bars
 */
public class NoInsetFrameLayout extends FrameLayout {
    public NoInsetFrameLayout(@NonNull Context context) {
        super(context);
    }

    public NoInsetFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NoInsetFrameLayout(@NonNull Context context,
                              @Nullable AttributeSet attrs,
                              int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NoInsetFrameLayout(@NonNull Context context,
                              @Nullable AttributeSet attrs,
                              int defStyleAttr,
                              int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        return super.onApplyWindowInsets(
                insets.replaceSystemWindowInsets(0, 0, 0, insets.getSystemWindowInsetBottom()));
    }
}
