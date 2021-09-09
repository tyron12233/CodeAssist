package tellh.com.recyclertreeview_lib;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Created by tlh on 2016/10/1 :)
 */
public class TreeViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String KEY_IS_EXPAND = "IS_EXPAND";
    private final List<? extends TreeViewBinder<? extends RecyclerView.ViewHolder>> viewBinders;
    private final List<TreeNode<? extends LayoutItemType>> displayNodes;
    private int padding = 30;
    private OnTreeNodeListener onTreeNodeListener;
    private boolean toCollapseChild;

    public TreeViewAdapter(List<? extends TreeViewBinder<? extends RecyclerView.ViewHolder>> viewBinders) {
        this(null, viewBinders);
    }

    public TreeViewAdapter(List<TreeNode<? extends LayoutItemType>> nodes, List<? extends TreeViewBinder <? extends RecyclerView.ViewHolder>> viewBinders) {
        displayNodes = new ArrayList<>();
        if (nodes != null)
            findDisplayNodes(nodes);
        this.viewBinders = viewBinders;
    }

    /**
     * 从nodes的结点中寻找展开了的非叶结点，添加到displayNodes中。
     *
     * @param nodes 基准点
     */
    private void findDisplayNodes(List<? extends TreeNode<? extends LayoutItemType>> nodes) {
        for (TreeNode<? extends LayoutItemType> node : nodes) {
            displayNodes.add(node);
            if (!node.isLeaf() && node.isExpand())
                findDisplayNodes(node.getChildList());
        }
    }

    @Override
    public int getItemViewType(int position) {
        return displayNodes.get(position).getContent().getLayoutId();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(viewType, parent, false);
        if (viewBinders.size() == 1)
            return viewBinders.get(0).provideViewHolder(v);
        for (TreeViewBinder<?> viewBinder : viewBinders) {
            if (viewBinder.getLayoutId() == viewType)
                return viewBinder.provideViewHolder(v);
        }
        return viewBinders.get(0).provideViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            Bundle b = (Bundle) payloads.get(0);
            for (String key : b.keySet()) {
                if (KEY_IS_EXPAND.equals(key)) {
                    if (onTreeNodeListener != null)
                        onTreeNodeListener.onToggle(b.getBoolean(key), holder);
                }
            }
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(final RecyclerView.ViewHolder holder, int pos) {
        holder.itemView.setPaddingRelative(displayNodes.get(pos).getHeight() * padding, 3, 3, 3);
        holder.itemView.setOnClickListener(v -> {
            TreeNode<? extends LayoutItemType> selectedNode = displayNodes.get(holder.getBindingAdapterPosition());
            // Prevent multi-click during the short interval.
            try {
                long lastClickTime = (long) holder.itemView.getTag();
                if (System.currentTimeMillis() - lastClickTime < 500)
                    return;
            } catch (Exception e) {
                holder.itemView.setTag(System.currentTimeMillis());
            }
            holder.itemView.setTag(System.currentTimeMillis());

            if (onTreeNodeListener != null && onTreeNodeListener.onClick(selectedNode, holder))
                return;
            if (selectedNode.isLeaf())
                return;
            // This TreeNode was locked to click.
            if (selectedNode.isLocked()) return;
            boolean isExpand = selectedNode.isExpand();
            int positionStart = displayNodes.indexOf(selectedNode) + 1;
            if (!isExpand) {
                notifyItemRangeInserted(positionStart, addChildNodes(selectedNode, positionStart));
            } else {
                notifyItemRangeRemoved(positionStart, removeChildNodes(selectedNode, true));
            }
        });

        holder.itemView.setOnLongClickListener(view -> {
            if (onTreeNodeListener != null) {
                TreeNode<? extends LayoutItemType> node = displayNodes.get(holder.getBindingAdapterPosition());
                return onTreeNodeListener.onLongClick(node, holder);
            }
            return false;
        });
        for (TreeViewBinder viewBinder : viewBinders) {
            if (viewBinder.getLayoutId() == displayNodes.get(holder.getBindingAdapterPosition()).getContent().getLayoutId())
                viewBinder.bindView(holder, holder.getBindingAdapterPosition(), displayNodes.get(holder.getBindingAdapterPosition()));
        }
    }

    public int addChildNodes(TreeNode<? extends LayoutItemType> pNode, int startIndex) {
        List<? extends TreeNode<? extends LayoutItemType>> childList = pNode.getChildList();
        int addChildCount = 0;
        for (TreeNode<? extends LayoutItemType> treeNode : childList) {
            displayNodes.add(startIndex + addChildCount++, treeNode);
            if (treeNode.isExpand()) {
                addChildCount += addChildNodes(treeNode, startIndex + addChildCount);
            }
        }
        if (!pNode.isExpand())
            pNode.toggle();
        return addChildCount;
    }

    public int getIndex(TreeNode<? extends LayoutItemType> node) {
        return displayNodes.indexOf(node) + 1;
    }

    public int removeChildNodes(TreeNode<? extends LayoutItemType> pNode) {
        return removeChildNodes(pNode, true);
    }

    public int addChildNode(TreeNode pNode, TreeNode<? extends LayoutItemType> childNode) {
        TreeNode node = pNode.addChild(childNode);

        int parentIndex = displayNodes.indexOf(pNode);
        displayNodes.add(parentIndex + 1, childNode);

        return parentIndex + 1;
    }

    public int removeChildNode(TreeNode<? extends LayoutItemType> childNode) {
        TreeNode<? extends LayoutItemType> pNode = childNode.getParent();
        pNode.getChildList().remove(childNode);

        int index = displayNodes.indexOf(childNode);
        displayNodes.remove(childNode);

        return index;
    }

    private int removeChildNodes(TreeNode<? extends LayoutItemType> pNode, boolean shouldToggle) {
        if (pNode.isLeaf())
            return 0;
        List<? extends TreeNode<? extends LayoutItemType>> childList = pNode.getChildList();
        int removeChildCount = childList.size();
        displayNodes.removeAll(childList);
        for (TreeNode<? extends LayoutItemType> child : childList) {
            if (child.isExpand()) {
                if (toCollapseChild)
                    child.toggle();
                removeChildCount += removeChildNodes(child, false);
            }
        }
        if (shouldToggle)
            pNode.toggle();
        return removeChildCount;
    }

    @Override
    public int getItemCount() {
        return displayNodes == null ? 0 : displayNodes.size();
    }

    public void setPadding(int padding) {
        this.padding = padding;
    }

    public void ifCollapseChildWhileCollapseParent(boolean toCollapseChild) {
        this.toCollapseChild = toCollapseChild;
    }

    public void setOnTreeNodeListener(OnTreeNodeListener onTreeNodeListener) {
        this.onTreeNodeListener = onTreeNodeListener;
    }

    public interface OnTreeNodeListener {
        /**
         * called when TreeNodes were clicked.
         * @return weather consume the click event.
         */
        boolean onClick(TreeNode<? extends LayoutItemType> node, RecyclerView.ViewHolder holder);

        /**
         * called when TreeNodes were toggle.
         * @param isExpand the status of TreeNodes after being toggled.
         */
        void onToggle(boolean isExpand, RecyclerView.ViewHolder holder);

        boolean onLongClick(TreeNode<? extends LayoutItemType> node, RecyclerView.ViewHolder holder);
    }

    public void refresh(List<TreeNode<? extends LayoutItemType>> treeNodes) {
        displayNodes.clear();
        findDisplayNodes(treeNodes);
        notifyDataSetChanged();
    }

    public Iterator<TreeNode<? extends LayoutItemType>> getDisplayNodesIterator() {
        return displayNodes.iterator();
    }

    private void notifyDiff(final List<TreeNode<? extends LayoutItemType>> temp) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return temp.size();
            }

            @Override
            public int getNewListSize() {
                return displayNodes.size();
            }

            // judge if the same items
            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return TreeViewAdapter.this.areItemsTheSame(temp.get(oldItemPosition), displayNodes.get(newItemPosition));
            }

            // if they are the same items, whether the contents has bean changed.
            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return TreeViewAdapter.this.areContentsTheSame(temp.get(oldItemPosition), displayNodes.get(newItemPosition));
            }

            @Nullable
            @Override
            public Object getChangePayload(int oldItemPosition, int newItemPosition) {
                return TreeViewAdapter.this.getChangePayload(temp.get(oldItemPosition), displayNodes.get(newItemPosition));
            }
        });
        diffResult.dispatchUpdatesTo(this);
    }

    private Object getChangePayload(TreeNode<? extends LayoutItemType> oldNode, TreeNode<? extends LayoutItemType> newNode) {
        Bundle diffBundle = new Bundle();
        if (newNode.isExpand() != oldNode.isExpand()) {
            diffBundle.putBoolean(KEY_IS_EXPAND, newNode.isExpand());
        }
        if (diffBundle.size() == 0)
            return null;
        return diffBundle;
    }

    // For DiffUtil, if they are the same items, whether the contents has bean changed.
    private boolean areContentsTheSame(TreeNode<? extends LayoutItemType> oldNode, TreeNode<? extends LayoutItemType> newNode) {
        return oldNode.getContent() != null && oldNode.getContent().equals(newNode.getContent())
                && oldNode.isExpand() == newNode.isExpand();
    }

    // judge if the same item for DiffUtil
    private boolean areItemsTheSame(TreeNode<? extends LayoutItemType> oldNode, TreeNode<? extends LayoutItemType> newNode) {
        return oldNode.getContent() != null && oldNode.getContent().equals(newNode.getContent());
    }

    /**
     * collapse all root nodes.
     */
    public void collapseAll() {
        // Back up the nodes are displaying.
        List<TreeNode<? extends LayoutItemType>> temp = backupDisplayNodes();
        //find all root nodes.
        List<TreeNode<? extends LayoutItemType>> roots = new ArrayList<>();
        for (TreeNode<? extends LayoutItemType> displayNode : displayNodes) {
            if (displayNode.isRoot())
                roots.add(displayNode);
        }
        //Close all root nodes.
        for (TreeNode<? extends LayoutItemType> root : roots) {
            if (root.isExpand())
                removeChildNodes(root);
        }
        notifyDiff(temp);
    }

    @NonNull
    private List<TreeNode<? extends LayoutItemType>> backupDisplayNodes() {
        List<TreeNode<? extends LayoutItemType>> temp = new ArrayList<>();
        for (TreeNode<? extends LayoutItemType> displayNode : displayNodes) {
            try {
                temp.add(displayNode.clone());
            } catch (CloneNotSupportedException e) {
                temp.add(displayNode);
            }
        }
        return temp;
    }

    public void collapseNode(TreeNode<? extends LayoutItemType> pNode) {
        List<TreeNode<? extends LayoutItemType>> temp = backupDisplayNodes();
        removeChildNodes(pNode);
        notifyDiff(temp);
    }

    public void collapseBrotherNode(TreeNode<? extends LayoutItemType> pNode) {
        List<TreeNode<? extends LayoutItemType>> temp = backupDisplayNodes();
        if (pNode.isRoot()) {
            List<TreeNode<? extends LayoutItemType>> roots = new ArrayList<>();
            for (TreeNode<? extends LayoutItemType> displayNode : displayNodes) {
                if (displayNode.isRoot())
                    roots.add(displayNode);
            }
            //Close all root nodes.
            for (TreeNode<? extends LayoutItemType> root : roots) {
                if (root.isExpand() && !root.equals(pNode))
                    removeChildNodes(root);
            }
        } else {
            TreeNode<? extends LayoutItemType> parent = pNode.getParent();
            if (parent == null)
                return;
            List<? extends TreeNode<? extends LayoutItemType>> childList = parent.getChildList();
            for (TreeNode<? extends LayoutItemType> node : childList) {
                if (node.equals(pNode) || !node.isExpand())
                    continue;
                removeChildNodes(node);
            }
        }
        notifyDiff(temp);
    }

}
