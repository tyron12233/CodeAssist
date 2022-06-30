package com.tyron.code.ui.file.tree;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.ThemeUtils;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.tyron.actions.ActionManager;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.R;
import com.tyron.code.event.EventManager;
import com.tyron.code.event.EventReceiver;
import com.tyron.code.event.SubscriptionReceipt;
import com.tyron.code.event.Unsubscribe;
import com.tyron.code.ui.file.event.RefreshRootEvent;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.file.CommonFileKeys;
import com.tyron.code.ui.file.FileViewModel;
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewBinder.TreeFileNodeListener;
import com.tyron.code.ui.file.tree.binder.TreeFileNodeViewFactory;
import com.tyron.code.ui.file.tree.model.TreeFile;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.Executors;

public class TreeFileManagerFragment extends Fragment {

    /**
     * @deprecated Instantiate this fragment directly without arguments and
     * use {@link FileViewModel} to update the nodes
     */
    @Deprecated
    public static TreeFileManagerFragment newInstance(File root) {
        TreeFileManagerFragment fragment = new TreeFileManagerFragment();
        Bundle args = new Bundle();
        args.putSerializable("rootFile", root);
        fragment.setArguments(args);
        return fragment;
    }

    private MainViewModel mMainViewModel;
    private FileViewModel mFileViewModel;
    private TreeView<TreeFile> treeView;

    public TreeFileManagerFragment() {
        super(R.layout.tree_file_manager_fragment);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        mFileViewModel = new ViewModelProvider(requireActivity()).get(FileViewModel.class);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ViewCompat.requestApplyInsets(view);
        UiUtilsKt.addSystemWindowInsetToPadding(view, false, true, false, true);

        SwipeRefreshLayout refreshLayout = view.findViewById(R.id.refreshLayout);
        refreshLayout.setOnRefreshListener(() -> partialRefresh(() -> {
            refreshLayout.setRefreshing(false);
            treeView.refreshTreeView();
        }));


        treeView = new TreeView<>(
                requireContext(), TreeNode.root(Collections.emptyList()));

        HorizontalScrollView horizontalScrollView = view.findViewById(R.id.horizontalScrollView);
        horizontalScrollView.addView(treeView.getView(), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        treeView.getView().setNestedScrollingEnabled(false);

        EventManager eventManager = ApplicationLoader.getInstance()
                .getEventManager();
        eventManager.subscribeEvent(getViewLifecycleOwner(), RefreshRootEvent.class, (event, unsubscribe) -> {
            File refreshRoot = event.getRoot();
            TreeNode<TreeFile> currentRoot = treeView.getRoot();
            if (currentRoot != null && refreshRoot.equals(currentRoot.getValue().getFile())) {
                partialRefresh(() -> treeView.refreshTreeView());
            } else {
                ProgressManager.getInstance().runNonCancelableAsync(() -> {
                    TreeNode<TreeFile> node = TreeNode.root(TreeUtil.getNodes(refreshRoot));
                    ProgressManager.getInstance().runLater(() -> {
                        if (getActivity() == null) {
                            return;
                        }
                        treeView.refreshTreeView(node);
                    });
                });
            }
        });

        treeView.setAdapter(new TreeFileNodeViewFactory(new TreeFileNodeListener() {
            @Override
            public void onNodeToggled(TreeNode<TreeFile> treeNode, boolean expanded) {
                if (treeNode.isLeaf()) {
                    if (treeNode.getValue().getFile().isFile()) {
                        FileEditorManagerImpl.getInstance().openFile(requireContext(), treeNode.getValue().getFile(), true);
                    }
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
        mFileViewModel.getNodes().observe(getViewLifecycleOwner(), node -> {
            treeView.refreshTreeView(node);
        });
    }


    private void partialRefresh(Runnable callback) {
        ProgressManager.getInstance().runNonCancelableAsync(() -> {
            if (!treeView.getAllNodes().isEmpty()) {
                TreeNode<TreeFile> node = treeView.getAllNodes().get(0);
                TreeUtil.updateNode(node);
                ProgressManager.getInstance().runLater(() -> {
                    if (getActivity() == null) {
                        return;
                    }
                    callback.run();
                });
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    /**
     * Add menus to the current PopupMenu based on the current {@link TreeNode}
     *
     * @param popupMenu The PopupMenu to add to
     * @param node      The current TreeNode in the file tree
     */
    private void addMenus(PopupMenu popupMenu, TreeNode<TreeFile> node) {
        DataContext dataContext = DataContext.wrap(requireContext());
        dataContext.putData(CommonDataKeys.FILE, node.getContent().getFile());
        dataContext.putData(CommonDataKeys.PROJECT, ProjectManager.getInstance().getCurrentProject());
        dataContext.putData(CommonDataKeys.FRAGMENT, TreeFileManagerFragment.this);
        dataContext.putData(CommonDataKeys.ACTIVITY, requireActivity());
        dataContext.putData(CommonFileKeys.TREE_NODE, node);

        ActionManager.getInstance().fillMenu(dataContext,
                popupMenu.getMenu(), ActionPlaces.FILE_MANAGER,
                true,
                false);
    }

    public TreeView<TreeFile> getTreeView() {
        return treeView;
    }

    public MainViewModel getMainViewModel() {
        return mMainViewModel;
    }

    public FileViewModel getFileViewModel() {
        return mFileViewModel;
    }
}

