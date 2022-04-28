package com.tyron.actions.util;

import android.content.Context;
import android.view.View;

import com.tyron.actions.DataContext;

public class DataContextUtils {

    public static DataContext getDataContext(View view) {
        Context context = view.getContext();
        if (context instanceof DataContext) {
            return (DataContext) context;
        }

        return new DataContext(context);
    }
}
