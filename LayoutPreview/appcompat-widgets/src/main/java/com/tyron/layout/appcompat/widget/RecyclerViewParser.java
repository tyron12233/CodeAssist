package com.tyron.layout.appcompat.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layout.appcompat.view.ProteusRecyclerView;

public class RecyclerViewParser<V extends View> extends ViewTypeParser<V> {
    @NonNull
    @Override
    public String getType() {
        return RecyclerView.class.getName();
    }

    @Nullable
    @Override
    public String getParentType() {
        return ViewGroup.class.getName();
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context,
                                  @NonNull Layout layout,
                                  @NonNull ObjectValue data,
                                  @Nullable ViewGroup parent,
                                  int dataIndex) {
        return new ProteusRecyclerView(context);
    }

    @Override
    protected void addAttributeProcessors() {

        addAttributeProcessor("tools:listitem", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                String layoutName = value.replace("@layout/", "");
                if (view instanceof ProteusRecyclerView) {
                    ((ProteusRecyclerView) view).setListItem(layoutName);
                }
            }
        });
    }
}
