/*
 * Copyright 2016 - 2017 ShineM (Xinyuan)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under.
 */

package com.tyron.ui.treeview.base;

import android.view.View;

import com.tyron.ui.treeview.TreeNode;

/**
 * Created by zxy on 17/4/23.
 */

public abstract class BaseNodeViewFactory<D> {

    /**
    * The default implementation below behaves as in previous version when TreeViewAdapter.getItemViewType always returned the level,
    * but you can override it if you want some other viewType value to become the parameter to the method getNodeViewBinder.
    */
    public int getViewType(TreeNode<D> treeNode) {
        return treeNode.getLevel();
    }

    /**
     * If you want build a tree view,you must implement this factory method
     *
     * @param view  The parameter for BaseNodeViewBinder's constructor, do not use this for other
     *              purpose!
     * @param viewType The viewType value is the treeNode level in the default implementation.
     * @return BaseNodeViewBinder
     */
    public abstract BaseNodeViewBinder<D> getNodeViewBinder(View view, int viewType);


    /**
     * If you want build a tree view,you must implement this factory method
     *
     * @param level Level of view, returned from {@link #getViewType}
     * @return node layout id
     */
    public abstract int getNodeLayoutId(int level);

}
