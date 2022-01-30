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

import java.util.List;

import com.tyron.ui.treeview.TreeNode;

/**
 * Created by xinyuanzhong on 2017/4/27.
 */

public interface SelectableTreeAction<D> extends BaseTreeAction<D> {
    void selectNode(TreeNode<D> treeNode);

    void deselectNode(TreeNode<D> treeNode);

    void selectAll();

    void deselectAll();

    List<TreeNode<D>> getSelectedNodes();
}
