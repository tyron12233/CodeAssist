package com.tyron.code.util;
import android.util.TypedValue;
import com.tyron.code.ApplicationLoader;

public class AndroidUtilities {
    
    public static int dp(float px) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX,
                px, ApplicationLoader.applicationContext.getResources().getDisplayMetrics()));
    }
}
