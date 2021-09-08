package com.tyron.code.ui.file.tree;

import android.os.Bundle;
import android.view.ActionProvider;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.ProjectManager;
import com.tyron.code.R;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.template.java.JavaClassTemplate;
import com.tyron.code.ui.file.tree.binder.TreeBinder;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.util.AndroidUtilities;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tellh.com.recyclertreeview_lib.LayoutItemType;
import tellh.com.recyclertreeview_lib.TreeNode;
import tellh.com.recyclertreeview_lib.TreeViewAdapter;

public class TreeFileManagerFragment extends Fragment implements View.OnContextClickListener {

    public static TreeFileManagerFragment newInstance(File root) {
        TreeFileManagerFragment fragment = new TreeFileManagerFragment();
        Bundle args = new Bundle();
        args.putSerializable("rootFile", root);
        fragment.setArguments(args);
        return fragment;
    }

    private File mRootFile;

    private RecyclerView mListView;
    private TreeViewAdapter mAdapter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRootFile = (File) requireArguments().getSerializable("rootFile");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());

        mListView = new RecyclerView(requireContext());
        root.addView(mListView, new FrameLayout.LayoutParams(-1, -1));

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        LinearLayoutManager manager = new LinearLayoutManager(requireContext());

        mListView.setLayoutManager(manager);

        mAdapter = new TreeViewAdapter(new ArrayList<>(getNodes()), Collections.singletonList(new TreeBinder()));

        mAdapter.setOnTreeNodeListener(new TreeViewAdapter.OnTreeNodeListener() {
            @Override
            public boolean onClick(TreeNode<? extends LayoutItemType> treeNode, RecyclerView.ViewHolder viewHolder) {
                if (!treeNode.isLeaf()) {
                    //onToggle(!treeNode.isExpand(), viewHolder);
                    toggle(!treeNode.isExpand(), viewHolder, treeNode);
                } else {
                    openFile(((TreeFile) treeNode.getContent()).getFile());
                    return true;
                }
                return false;
            }

            @Override
            public void onToggle(boolean isExpand, RecyclerView.ViewHolder viewHolder) {
                toggle(isExpand, viewHolder, null);
            }

            @Override
            public boolean onLongClick(TreeNode<? extends LayoutItemType> node, RecyclerView.ViewHolder holder) {
                // noinspection unchecked
//                final TreeNode<TreeFile> fileNode = (TreeNode<TreeFile>) node;
//
//                mListView.setOnCreateContextMenuListener((contextMenu, view1, contextMenuInfo) -> {
//                    MenuInflater inflater = new MenuInflater(view1.getContext());
//                    inflater.inflate(R.menu.file_manager_menu, contextMenu);
//
//                    contextMenu.getItem(0).getSubMenu().getItem(0).setOnMenuItemClickListener(menuItem -> {
//                        CodeTemplate template = new JavaClassTemplate();
//                        File directory;
//                        if (node.isLeaf()) {
//                            directory = ((TreeFile) node.getParent().getContent()).getFile();
//                        } else {
//                            directory = ((TreeFile) node.getContent()).getFile();
//                        }
//                        String className = "TestClass";
//
//                        File file = null;
//                        try {
//                            file = ProjectManager.createClass(directory, className, template);
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//
//                        if (file != null) {
//                            TreeNode<TreeFile> newNode = new TreeNode<>(TreeFile.fromFile(file));
//                            mAdapter.notifyItemInserted(mAdapter.addChildNode(node.getParent(), newNode));
//                        }
//                        return true;
//                    });
//                });
//                int x = (int) holder.itemView.getX() + AndroidUtilities.dp(8);
//                int y = (int) holder.itemView.getY() + holder.itemView.getHeight();
//                mListView.showContextMenu(x, y);
                return true;
            }

            public void toggle(boolean isExpand, RecyclerView.ViewHolder viewHolder, TreeNode<? extends LayoutItemType> treeNode) {
                if (isExpand) {
                    expandRecursively(treeNode);
                }

                TreeBinder.ViewHolder holder = (TreeBinder.ViewHolder) viewHolder;
                int rotateDegree = isExpand ? 90 : -90;
                holder.arrow.animate()
                        .setDuration(180L)
                        .rotationBy(rotateDegree)
                        .start();
            }

            public void expandRecursively(TreeNode<? extends LayoutItemType> treeNode) {
                if (treeNode != null && !treeNode.isLeaf()) {
                    List<? extends TreeNode<? extends LayoutItemType>> children = treeNode.getChildList();

                    if (children != null && children.size() == 1) {
                        // noinspection unchecked
                        TreeNode<TreeFile> childNode = (TreeNode<TreeFile>) children.get(0);

                        if (childNode != null && !childNode.isLeaf()) {
                            childNode.expand();
                            expandRecursively(childNode);
                        }
                    }
                }
            }
        });
        mListView.setAdapter(mAdapter);

    }

    @Override
    public boolean onContextClick(View view) {
        return false;
    }

    /**
     * Sets the tree to be rooted at this file, calls refresh() after
     * @param file root file of the tree
     */
    public void setRoot(File file) {
        mRootFile = file;
        refresh();
    }

    public void refresh() {
        if (mAdapter != null) {
            List<TreeNode<TreeFile>> nodes = getNodes();
            mAdapter.refresh(new ArrayList<>(nodes));
        }
    }

    private List<TreeNode<TreeFile>> getNodes() {
        List<TreeNode<TreeFile>> nodes = new ArrayList<>();

        TreeNode<TreeFile> root = new TreeNode<>(TreeFile.fromFile(mRootFile));
        File[] childs = mRootFile.listFiles();
        if (childs != null) {
            for (File file : childs) {
                addNode(root, file);
            }
        }
        nodes.add(root);
        return nodes;
    }

    private void addNode(TreeNode<TreeFile> node, File file) {
        TreeNode<TreeFile> childNode = new TreeNode<>(TreeFile.fromFile(file));

        if (file.isDirectory()) {
            File[] childs = file.listFiles();
            if (childs != null) {
                for (File child : childs) {
                    addNode(childNode, child);
                }
            }
        }

        node.addChild(childNode);
    }

    private void openFile(File file) {
        Fragment parent = getParentFragment();

        if (parent != null) {
            if (parent instanceof MainFragment) {
                ((MainFragment) parent).openFile(file);
            }
        }
    }
}

