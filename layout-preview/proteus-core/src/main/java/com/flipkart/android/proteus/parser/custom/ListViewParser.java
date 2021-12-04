package com.flipkart.android.proteus.parser.custom;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.custom.ProteusListView;

public class ListViewParser<T extends View> extends ViewTypeParser<T> {

    @NonNull
    @Override
    public String getType() {
        return "android.widget.ListView";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.view.ViewGroup";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusListView(context);
    }

    @Override
    protected void addAttributeProcessors() {
        addAttributeProcessor("tools:listitem", new StringAttributeProcessor<T>() {
            @Override
            public void setString(T view, String value) {
                String layoutName = value.replace("@layout/", "");
                if (view instanceof ProteusListView) {
                    ((ProteusListView) view).setListItem(layoutName);
                }
            }
        });
    }
}
