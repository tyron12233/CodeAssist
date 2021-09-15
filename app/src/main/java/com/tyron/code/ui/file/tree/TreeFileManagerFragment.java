package com.tyron.code.ui.file.tree;

import android.os.Bundle;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tyron.ProjectManager;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.ui.file.CreateClassDialogFragment;
import com.tyron.code.ui.file.tree.binder.TreeBinder;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.code.util.ProjectUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tellh.com.recyclertreeview_lib.LayoutItemType;
import tellh.com.recyclertreeview_lib.TreeNode;
import tellh.com.recyclertreeview_lib.TreeViewAdapter;

public class TreeFileManagerFragment extends Fragment {

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
    private MainViewModel mMainViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRootFile = (File) requireArguments().getSerializable("rootFile");
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
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

            @SuppressWarnings("unchecked")
            @Override
            public boolean onLongClick(TreeNode<? extends LayoutItemType> node, RecyclerView.ViewHolder holder) {
                mListView.setOnCreateContextMenuListener((contextMenu, view1, contextMenuInfo) -> {
                    addMenus(contextMenu, (TreeNode<TreeFile>) node);
                });
                int x = (int) holder.itemView.getX() + AndroidUtilities.dp(8);
                int y = (int) holder.itemView.getY() + holder.itemView.getHeight();
                mListView.showContextMenu(x, y);
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

    /**
     * Add menus to the current ContextMenu based on the current {@link TreeNode}
     * @param contextMenu The ContextMenu to add to
     * @param node The current TreeNode in the file tree
     */
    private void addMenus(ContextMenu contextMenu, TreeNode<TreeFile> node) {
        File currentFile = node.getContent().getFile();

        SubMenu newSubMenu = contextMenu.addSubMenu("New");
        newSubMenu.add("Java class")
                .setOnMenuItemClickListener(menuItem -> {
                    CreateClassDialogFragment fragment = new CreateClassDialogFragment();
                    fragment.show(getChildFragmentManager(), "create_class_fragment");

                    fragment.setOnClassCreatedListener((className, template) -> {

                        TreeNode<?> selectedNode = node.isLeaf() ? node.getParent() : node;
                        File directory = getDirectory(node);
                        try {
                            File createdFile = ProjectManager.createClass(directory, className, template);
                            TreeNode<TreeFile> newNode = new TreeNode<>(TreeFile.fromFile(createdFile));
                            mAdapter.notifyItemInserted(mAdapter.addChildNode(selectedNode, newNode));
                            mMainViewModel.addFile(createdFile);
                            FileManager.getInstance().addJavaFile(createdFile);
                        } catch (IOException e) {
                            ApplicationLoader.showToast("Unable to create class: " + e.getMessage());
                        }
                    });
                    return true;
                });

        contextMenu.add("Delete")
                .setOnMenuItemClickListener(menuItem -> {

                    //TODO: IMPROVE
                    AlertDialog dialog = new AlertDialog.Builder(requireContext())
                            .setMessage(String.format(getString(R.string.dialog_confirm_delete), currentFile.getName()))
                            .setPositiveButton(getString(R.string.dialog_delete), (d, which) -> {
                                String packageName = ProjectUtils.getPackageName(currentFile);
                                if (packageName != null) {
                                    if (node.isLeaf() || !node.isExpand()) {
                                        mMainViewModel.removeFile(currentFile);
                                        FileManager.getInstance().removeJavaFile(packageName);
                                        mAdapter.notifyItemRemoved(mAdapter.removeChildNode(node));
                                        currentFile.delete();
                                    } else {
                                        // need help deleting a directory :(
//                                        try {
//                                            List<File> deletedFiles = FileManager.getInstance().deleteDirectory(currentFile);
//                                            for (File file : deletedFiles) {
//                                                mMainViewModel.removeFile(file);
//                                            }
//                                            if (deletedFiles.isEmpty()) {
//                                                mAdapter.notifyItemRemoved(mAdapter.removeChildNode(node) - 1);
//                                            } else {
//                                                int startPosition = mAdapter.getIndex(node) - 1;
//                                                node.getParent().getChildList().remove(node);
//                                                mAdapter.notifyItemRangeRemoved(startPosition, mAdapter.removeChildNodes(node, false) + 1);
//                                            }
//                                        } catch (IOException e) {
//                                            ApplicationLoader.showToast(e.getMessage());
//                                        }
                                    }
                                } else {
//                                    int startPosition = mAdapter.getIndex(node);
//                                    try {
//                                        if (node.isLeaf() || !node.isExpand()) {
//                                            mAdapter.removeChildNodes(node);
//                                            mAdapter.notifyItemRemoved(startPosition - 1);
//                                            FileUtils.delete(currentFile);
//                                        } else {
//                                            node.getParent().getChildList().remove(node);
//                                            mAdapter.notifyItemRangeRemoved(startPosition - 1, mAdapter.removeChildNodes(node, false) + 1);
//                                            FileUtils.deleteDirectory(currentFile);
//
//                                        }
//                                    } catch (IOException e) {
//                                        e.printStackTrace();
//                                    }
                                }
                            })
                            .show();
                    return true;
                });
    }

    /**
     * Gets the parent directory of a node, if the node is already a directory then
     * it is returned
     * @param node the node to search
     * @return parent directory or itself if its already a directory
     */
    private File getDirectory(TreeNode<TreeFile> node) {
        if (node.isLeaf()) {
            return node.getParent().getContent().getFile();
        } else {
            return node.getContent().getFile();
        }
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
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
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

