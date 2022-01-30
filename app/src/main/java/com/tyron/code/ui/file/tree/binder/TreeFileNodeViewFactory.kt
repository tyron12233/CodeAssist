package com.tyron.code.ui.file.tree.binder

import android.view.View
import com.tyron.code.R
import com.tyron.ui.treeview.base.BaseNodeViewFactory
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewBinder.TreeFileNodeListener
import com.tyron.code.ui.file.tree.model.TreeFile

class TreeFileNodeViewFactory(
    private var nodeListener: TreeFileNodeListener
): BaseNodeViewFactory<TreeFile>() {

    override fun getNodeViewBinder(view: View, level: Int) =
        TreeFileNodeViewBinder(view, level, nodeListener)

    override fun getNodeLayoutId(level: Int) = R.layout.file_manager_item

}
