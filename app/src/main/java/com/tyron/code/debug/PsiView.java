package com.tyron.code.debug;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.code.R;
import com.tyron.code.util.UiUtilsKt;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.ui.treeview.base.BaseNodeViewBinder;
import com.tyron.ui.treeview.base.BaseNodeViewFactory;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeAnyChangeAbstractAdapter;
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeEvent;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SuppressLint("ViewConstructor")
public class PsiView extends FrameLayout {

    private final PsiFile file;
    private final TreeView<PsiElement> treeView;

    TreeNode<PsiElement> root;

    public PsiView(Context context, PsiFile file) {
        super(context);
        this.file = file;

        root = createNode(file, null);

        treeView = new TreeView<>(context, root);
        addView(treeView.getView(), new LayoutParams(-1, -1));

        treeView.setAdapter(new BaseNodeViewFactory<>() {
            @Override
            public BaseNodeViewBinder<PsiElement> getNodeViewBinder(View view, int viewType) {
                return new BaseNodeViewBinder<>(view) {
                    @Override
                    public void bindView(TreeNode<PsiElement> treeNode) {
                        UiUtilsKt.setMargins(view, AndroidUtilities.dp(15) * treeNode.getLevel(), 0, 0 ,0);



                        ImageView arrow = view.findViewById(R.id.arrow);
                        arrow.setImageResource(R.drawable.ic_baseline_keyboard_arrow_right_24);
                        arrow.setRotation(treeNode.isExpanded() ? 90f : 0f);
                        arrow.setVisibility(treeNode.isLeaf() ? View.INVISIBLE : View.VISIBLE);

                        TextView name = view.findViewById(R.id.name);

                        PsiElement value = treeNode.getValue();
                        name.setText(value.toString());
                    }
                };
            }

            @Override
            public int getNodeLayoutId(int level) {
                return R.layout.file_manager_item;
            }
        });

        PsiManager.getInstance(file.getProject()).addPsiTreeChangeListener(new PsiTreeAnyChangeAbstractAdapter() {
            @Override
            protected void onChange(@Nullable PsiFile psiFile) {
                root = createNode(psiFile, null);
                treeView.refreshTreeView(root);
            }

            @Override
            public void childAdded(@NonNull PsiTreeChangeEvent event) {
                Optional<TreeNode<PsiElement>> oldParentResult = treeView.getAllNodes()
                        .stream()
                        .filter(node -> node.getContent().equals(event.getOldParent()))
                        .findAny();
                oldParentResult.ifPresent(oldParent -> {
                    TreeNode<PsiElement> node = createNode(event.getElement(), oldParent);
                    oldParent.addChild(node);
                });
            }

            @Override
            public void childRemoved(@NonNull PsiTreeChangeEvent event) {
                Optional<TreeNode<PsiElement>> oldNodeResult = treeView.getAllNodes()
                        .stream()
                        .filter(node -> node.getContent().equals(event.getElement()))
                        .findAny();
                oldNodeResult.ifPresent(oldNode -> oldNode.getParent().removeChild(oldNode));
            }
        });
    }

    private TreeNode<PsiElement> createNode(PsiElement current, @Nullable TreeNode<PsiElement> parent) {
        int level = parent != null ? parent.getLevel() + 1 : 0;
        TreeNode<PsiElement> newNode = new TreeNode<>(current, level);

        for (PsiElement child : current.getChildren()) {
            TreeNode<PsiElement> childNode = createNode(child, newNode);
            newNode.addChild(childNode);
        }

        return newNode;
    }
}
