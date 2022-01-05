package com.tyron.layout.appcompat.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.button.MaterialButton;
import com.tyron.layout.appcompat.view.ProteusMaterialButton;

public class MaterialButtonParser<V extends View> extends ViewTypeParser<V> {
    @NonNull
    @Override
    public String getType() {
        return MaterialButton.class.getName();
    }

    @Nullable
    @Override
    public String getParentType() {
        return AppCompatButton.class.getName();
    }

    @Nullable
    @Override
    protected String getDefaultStyleName() {
        return "?attr/materialButtonStyle";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context,
                                  @NonNull Layout layout,
                                  @NonNull ObjectValue data,
                                  @Nullable ViewGroup parent,
                                  int dataIndex) {
        return new ProteusMaterialButton(context);
    }

    @Override
    protected void addAttributeProcessors() {
        // TODO: add attributes
    }
}
