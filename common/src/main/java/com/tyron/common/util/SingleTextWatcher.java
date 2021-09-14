package com.tyron.common.util;

import android.text.Editable;
import android.text.TextWatcher;

/**
 * Utility class if you want to only override one method of {@link TextWatcher}
 */
public class SingleTextWatcher implements TextWatcher {

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    @Override
    public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

    }

    @Override
    public void afterTextChanged(Editable editable) {

    }
}
