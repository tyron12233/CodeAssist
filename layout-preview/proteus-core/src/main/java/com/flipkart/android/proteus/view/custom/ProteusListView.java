package com.flipkart.android.proteus.view.custom;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.UnknownView;

public class ProteusListView extends ListView implements ProteusView {

    private Manager manager;
    private String mLayoutPreviewName;

    public ProteusListView(Context context) {
        super(context);
    }

    public ProteusListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProteusListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ProteusListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public Manager getViewManager() {
        return manager;
    }

    @Override
    public void setViewManager(@NonNull Manager manager) {
        this.manager = manager;
    }

    @NonNull
    @Override
    public View getAsView() {
        return this;
    }

    public void setListItem(String layoutName) {
        mLayoutPreviewName = layoutName;
        setAdapter(new Adapter());
    }

    private class Adapter implements ListAdapter {

        @Override
        public boolean areAllItemsEnabled() {
            return true;
        }

        @Override
        public boolean isEnabled(int i) {
            return true;
        }

        @Override
        public void registerDataSetObserver(DataSetObserver dataSetObserver) {

        }

        @Override
        public void unregisterDataSetObserver(DataSetObserver dataSetObserver) {

        }

        @Override
        public int getCount() {
            if (mLayoutPreviewName == null) {
                return 0;
            }
            return 12;
        }

        @Override
        public Object getItem(int i) {
            return null;
        }

        @Override
        public long getItemId(int i) {
            return 1;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @SuppressLint("ViewHolder")
        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ProteusContext context = manager.getContext();

            if (mLayoutPreviewName.equals(manager.getDataContext().getData().getAsString("layout_name"))) {
                return new UnknownView(context, "Unable to find layout: " + mLayoutPreviewName);
            }

            Layout layout = context.getLayout(mLayoutPreviewName);
            if (layout == null) {
                return new UnknownView(context, "Unable to find layout: " + mLayoutPreviewName);
            }
            return context.getInflater()
                    .inflate(layout, new ObjectValue())
                    .getAsView();
        }

        @Override
        public int getItemViewType(int i) {
            return 0;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return mLayoutPreviewName == null;
        }
    }
}
