package com.tyron.layout.appcompat.widget;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.textfield.TextInputEditText;
import com.tyron.layout.appcompat.view.ProteusTextInputEditText;

public class TextInputEditTextParser extends ViewTypeParser<TextInputEditText> {
    @NonNull
    @Override
    public String getType() {
        return TextInputEditText.class.getName();
    }

    @Nullable
    @Override
    public String getParentType() {
        return AppCompatEditText.class.getName();
    }

    @Nullable
    @Override
    protected String getDefaultStyleName() {
        return "?attr/editTextStyle";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout,
                                  @NonNull ObjectValue data, @Nullable ViewGroup parent,
                                  int dataIndex) {
        return new ProteusTextInputEditText(context);
    }

    @Override
    protected void addAttributeProcessors() {

    }
}
