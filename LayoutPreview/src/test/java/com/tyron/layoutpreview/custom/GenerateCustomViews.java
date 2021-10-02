package com.tyron.layoutpreview.custom;

import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.CustomView;
import com.tyron.layoutpreview.model.Format;

import java.util.Collections;

public class GenerateCustomViews {

    public void createConstraintLayout() {
        CustomView customView = new CustomView();
        customView.setType("androidx.constraintlayout.widget.ConstraintLayout");
        customView.setParentType("ViewGroup");
    }
}
