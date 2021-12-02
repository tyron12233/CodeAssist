package com.tyron.layout.appcompat.view;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.view.UnknownView;

public class ProteusRecyclerView extends RecyclerView implements ProteusView {

    private String mLayoutPreviewName;
    private Manager manager;
    private int itemCount = 10;

    public ProteusRecyclerView(@NonNull Context context) {
        super(context);
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
        setLayoutManager(new LinearLayoutManager(getContext()));
        setAdapter(new PreviewAdapter());
    }

    public class PreviewAdapter extends Adapter<PreviewAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frameLayout = new FrameLayout(parent.getContext());
            return new ViewHolder(frameLayout);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {

        }

        @Override
        public int getItemCount() {
            return itemCount;
        }

        private View getView(ProteusContext context) {
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

        private class ViewHolder extends RecyclerView.ViewHolder {

            public ViewHolder(@NonNull FrameLayout itemView) {
                super(itemView);

                ProteusContext context = manager.getContext();
                itemView.addView(getView(context));
            }
        }
    }
}
