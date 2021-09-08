package tellh.com.recyclertreeview_lib;

import android.view.View;

import androidx.annotation.IdRes;
import androidx.recyclerview.widget.RecyclerView;


public abstract class TreeViewBinder<VH extends RecyclerView.ViewHolder> implements LayoutItemType {
    public abstract VH provideViewHolder(View itemView);

    public abstract void bindView(VH holder, int position, TreeNode<? extends LayoutItemType> node);

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(View rootView) {
            super(rootView);
        }

        protected <T extends View> T findViewById(@IdRes int id) {
            return itemView.findViewById(id);
        }
    }

}