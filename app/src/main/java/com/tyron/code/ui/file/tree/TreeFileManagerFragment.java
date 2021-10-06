package com.tyron.code.ui.file.tree;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.tyron.ProjectManager;
import com.tyron.builder.parser.FileManager;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.ui.component.tree.TreeNode;
import com.tyron.code.ui.component.tree.TreeView;
import com.tyron.code.ui.file.CreateClassDialogFragment;
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewBinder.TreeFileNodeListener;
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewFactory;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.main.MainFragment;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.util.AndroidUtilities;
import com.tyron.common.util.StringSearch;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import kotlin.io.FileWalkDirection;
import kotlin.io.FilesKt;

public class TreeFileManagerFragment extends Fragment {

    public static TreeFileManagerFragment newInstance(File root) {
        TreeFileManagerFragment fragment = new TreeFileManagerFragment();
        Bundle args = new Bundle();
        args.putSerializable("rootFile", root);
        fragment.setArguments(args);
        return fragment;
    }

    private File mRootFile;
    private MainViewModel mMainViewModel;

    private TreeView<TreeFile> treeView;

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

        treeView = new TreeView<>(
                requireContext(), TreeNode.root(getNodes())
        );

        root.addView(treeView.getView(), new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        treeView.setAdapter(new TreeFileNodeViewFactory(new TreeFileNodeListener() {
            @Override
            public void onNodeToggled(TreeNode<TreeFile> treeNode, boolean expanded) {
                if (treeNode.isLeaf()) {
                    openFile(treeNode.getContent().getFile());
                }
            }

            @Override
            public boolean onNodeLongClicked(View view, TreeNode<TreeFile> treeNode, boolean expanded) {
                PopupMenu popupMenu = new PopupMenu(requireContext(), view);
                addMenus(popupMenu, treeNode);
                popupMenu.show();

                return true;
            }
        }));

    }

    /**
     * Add menus to the current PopupMenu based on the current {@link TreeNode}
     * @param popupMenu The PopupMenu to add to
     * @param node The current TreeNode in the file tree
     */
    // TODO: simplify
    private void addMenus(PopupMenu popupMenu, TreeNode<TreeFile> node) {
        File currentFile = node.getContent().getFile();

        if (currentFile.isDirectory()) {
            SubMenu newSubMenu = popupMenu.getMenu().addSubMenu("New");
            newSubMenu.add("Java class")
                    .setOnMenuItemClickListener(menuItem -> {
                        CreateClassDialogFragment fragment = new CreateClassDialogFragment();
                        fragment.show(getChildFragmentManager(), "create_class_fragment");

                        fragment.setOnClassCreatedListener((className, template) -> {

                            File directory = getDirectory(node);
                            try {
                                File createdFile = ProjectManager.createClass(directory, className, template);
                                TreeNode<TreeFile> newNode = new TreeNode<>(
                                        TreeFile.fromFile(createdFile),
                                        node.getLevel() + 1
                                );

                                treeView.addNode(node, newNode);
                                treeView.refreshTreeView();

                                mMainViewModel.addFile(createdFile);
                                FileManager.getInstance().addJavaFile(createdFile);
                            } catch (IOException e) {
                                ApplicationLoader.showToast("Unable to create class: " + e.getMessage());
                            }
                        });
                        return true;
                    });
        }

        popupMenu.getMenu().add("Copy path")
                .setOnMenuItemClickListener(menuItem -> {
                    AndroidUtilities.copyToClipboard(currentFile.getAbsolutePath(), true);

                    return true;
                });

        popupMenu.getMenu().add("Delete")
                .setOnMenuItemClickListener(menuItem -> {

                    new AlertDialog.Builder(requireContext())
                            .setMessage(String.format(getString(R.string.dialog_confirm_delete), currentFile.getName()))
                            .setPositiveButton(getString(R.string.dialog_delete), (d, which) -> {

                                deleteFiles(currentFile);
                                treeView.deleteNode(node);
                                treeView.refreshTreeView();

                            })
                            .show();
                    return true;
                });
    }

    private void deleteFiles(File fileToDelete) {
        FilesKt.walk(fileToDelete, FileWalkDirection.TOP_DOWN).iterator().forEachRemaining(file -> {
            if (file.getName().endsWith(".java")) { // todo: add .kt and .xml checks
                mMainViewModel.removeFile(file);

                String packageName = StringSearch.packageName(file);
                if (packageName != null) {
                    FileManager.getInstance().removeJavaFile(packageName);
                }
            }
        });

        FilesKt.deleteRecursively(fileToDelete);
    }

    /**
     * Gets the parent directory of a node, if the node is already a directory then
     * it is returned
     * @param node the node to search
     * @return parent directory or itself if its already a directory
     */
    private File getDirectory(TreeNode<TreeFile> node) {
        File file = node.getContent().getFile();
        if (file.isDirectory()) {
            return file;
        } else {
            return file.getParentFile();
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
        if (treeView != null) {
            treeView.refreshTreeView(TreeNode.root(getNodes()));
        }
    }

    private List<TreeNode<TreeFile>> getNodes() {
        List<TreeNode<TreeFile>> nodes = new ArrayList<>();

        TreeNode<TreeFile> root = new TreeNode<>(
                TreeFile.fromFile(mRootFile), 0
        );
        File[] childs = mRootFile.listFiles();
        if (childs != null) {
            for (File file : childs) {
                addNode(root, file, 1);
            }
        }
        nodes.add(root);
        return nodes;
    }

    private void addNode(TreeNode<TreeFile> node, File file, int level) {
        TreeNode<TreeFile> childNode = new TreeNode<>(
                TreeFile.fromFile(file), level
        );

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addNode(childNode, child, level + 1);
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

