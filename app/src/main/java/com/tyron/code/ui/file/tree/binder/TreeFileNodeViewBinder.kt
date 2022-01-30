package com.tyron.code.ui.file.tree.binder

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.tyron.code.R
import com.tyron.ui.treeview.TreeNode
import com.tyron.ui.treeview.base.BaseNodeViewBinder
import com.tyron.code.ui.file.tree.model.TreeFile
import com.tyron.code.util.dp
import com.tyron.code.util.setMargins

class TreeFileNodeViewBinder(
    itemView: View,
    private val level: Int,
    private val nodeListener: TreeFileNodeListener
): BaseNodeViewBinder<TreeFile>(itemView) {

    private lateinit var viewHolder: ViewHolder

    override fun bindView(treeNode: TreeNode<TreeFile>) {
        viewHolder = ViewHolder(itemView)

        viewHolder.rootView.setMargins(
            left = level * 15.dp
        )

        with(viewHolder.arrow) {
            setImageResource(R.drawable.ic_baseline_keyboard_arrow_right_24)
            rotation = if (treeNode.isExpanded) 90F else 0F
            visibility = if (treeNode.isLeaf) View.INVISIBLE else View.VISIBLE
        }

        val file = treeNode.content.file

        viewHolder.dirName.text = file.name

        with(viewHolder.icon) {
            setImageDrawable(treeNode.content.getIcon(context))
        }
    }

    override fun onNodeToggled(treeNode: TreeNode<TreeFile>, expand: Boolean) {
        viewHolder.arrow.animate()
            .rotation(if (expand) 90F else 0F)
            .setDuration(150)
            .start()

        nodeListener.onNodeToggled(treeNode, expand)
    }

    override fun onNodeLongClicked(view: View, treeNode: TreeNode<TreeFile>, expanded: Boolean): Boolean {
        return nodeListener.onNodeLongClicked(view, treeNode, expanded)
    }

    class ViewHolder(val rootView: View) {
        val arrow: ImageView = rootView.findViewById(R.id.arrow)
        val icon: ImageView = rootView.findViewById(R.id.icon)
        val dirName: TextView = rootView.findViewById(R.id.name)
    }

    interface TreeFileNodeListener {
        fun onNodeToggled(treeNode: TreeNode<TreeFile>?, expanded: Boolean)
        fun onNodeLongClicked(view: View?, treeNode: TreeNode<TreeFile>?, expanded: Boolean): Boolean
    }

}
