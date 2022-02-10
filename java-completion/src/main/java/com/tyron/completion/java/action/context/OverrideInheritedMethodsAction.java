package com.tyron.completion.java.action.context;

import static com.tyron.completion.java.util.DiagnosticUtil.MethodPtr;

import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.drawable.CircleDrawable;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.rewrite.OverrideInheritedMethod;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.java.util.PrintHelper;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Editor;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.ui.treeview.base.BaseNodeViewBinder;
import com.tyron.ui.treeview.base.BaseNodeViewFactory;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OverrideInheritedMethodsAction extends AnAction {

    public static final String ID = "javaOverrideInheritedMethodsAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        TreePath currentPath = event.getData(CommonJavaContextKeys.CURRENT_PATH);
        if (currentPath == null) {
            return;
        }

        if (!(currentPath.getLeaf() instanceof ClassTree)) {
            return;
        }


        JavaCompilerService compiler = event.getData(CommonJavaContextKeys.COMPILER);
        if (compiler == null) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_quickfix_override_inherited_methods_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        File file = e.getRequiredData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getRequiredData(CommonJavaContextKeys.COMPILER);
        TreePath currentPath = e.getRequiredData(CommonJavaContextKeys.CURRENT_PATH);

        List<MethodPtr> pointers = performInternal(compiler, file.toPath(), currentPath);
        Collections.reverse(pointers);

        TreeView<OverrideNode> treeView = new TreeView<>(e.getDataContext(),
                buildTreeNode(pointers));
        treeView.getView().setPaddingRelative(0, AndroidUtilities.dp(8), 0, 0);

        OverrideNodeViewFactory factory = new OverrideNodeViewFactory();
        treeView.setAdapter(factory);

        AlertDialog dialog = new MaterialAlertDialogBuilder(e.getDataContext())
                .setTitle(R.string.menu_quickfix_implement_abstract_methods_title)
                .setView(treeView.getView())
                .setNegativeButton(android.R.string.cancel, null)
                .show();

        factory.setOnTreeNodeClickListener((node, expand) -> {
            if (node.isLeaf()) {
                dialog.dismiss();

                OverrideNode value = node.getValue();
                MethodPtr ptr = value.getMethodPtr();
                JavaRewrite rewrite = new OverrideInheritedMethod(ptr.className, ptr.methodName,
                        ptr.erasedParameterTypes, file.toPath(), editor.getCaret().getStart());
                RewriteUtil.performRewrite(editor, file, compiler, rewrite);
            }
        });
    }

    private List<MethodPtr> performInternal(JavaCompilerService compiler, Path file,
                                            TreePath currentPath) {
        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
            Trees trees = Trees.instance(task.task);
            Element classElement = trees.getElement(currentPath);
            Elements elements = task.task.getElements();
            List<MethodPtr> methodPtrs = new ArrayList<>();
            for (Element member : elements.getAllMembers((TypeElement) classElement)) {
                if (member.getModifiers().contains(Modifier.FINAL)) {
                    continue;
                }
                if (member.getModifiers().contains(Modifier.STATIC)) {
                    continue;
                }
                if (member.getKind() != ElementKind.METHOD) {
                    continue;
                }
                ExecutableElement method = (ExecutableElement) member;
                TypeElement methodSource = (TypeElement) member.getEnclosingElement();

                if (methodSource.equals(classElement)) {
                    continue;
                }

                MethodPtr ptr = new MethodPtr(task.task, method);
                methodPtrs.add(ptr);
            }
            return methodPtrs;
        });
    }

    private static TreeNode<OverrideNode> buildTreeNode(List<MethodPtr> methodPtrs) {
        Map<String, List<MethodPtr>> classToPtr = new LinkedHashMap<>();

        for (MethodPtr methodPtr : methodPtrs) {
            classToPtr.compute(methodPtr.className, (s, list) -> {
                if (list == null) {
                    list = new ArrayList<>();
                }
                list.add(methodPtr);
                return list;
            });
        }

        TreeNode<OverrideNode> root = TreeNode.root();
        for (String key : classToPtr.keySet()) {
            List<MethodPtr> methods = classToPtr.get(key);
            if (methods != null && !methods.isEmpty()) {
                OverrideNode classNode = new OverrideNode(methods.iterator().next(), false);
                TreeNode<OverrideNode> classTreeNode = new TreeNode<>(classNode, 1);
                classTreeNode.setExpanded(true);
                for (MethodPtr method : methods) {
                    classTreeNode.addChild(new TreeNode<>(new OverrideNode(method, true), 2));
                }

                root.addChild(classTreeNode);
            }
        }
        return root;
    }

    private static class OverrideNode {

        private final MethodPtr ptr;
        private final boolean isMethod;

        public OverrideNode(MethodPtr value, boolean method) {
            ptr = value;
            isMethod = method;
        }

        public boolean isMethod() {
            return isMethod;
        }

        public MethodPtr getMethodPtr() {
            return ptr;
        }
    }

    private static class OverrideNodeViewFactory extends BaseNodeViewFactory<OverrideNode> {
        private TreeView.OnTreeNodeClickListener<OverrideNode> listener;

        @Override
        public BaseNodeViewBinder<OverrideNode> getNodeViewBinder(View view, int viewType) {
            return new OverrideNodeViewBinder(view, listener);
        }

        @Override
        public int getNodeLayoutId(int level) {
            return R.layout.override_node_item;
        }

        public void setOnTreeNodeClickListener(TreeView.OnTreeNodeClickListener<OverrideNode> listener) {
            this.listener = listener;
        }
    }

    private static class OverrideNodeViewBinder extends BaseNodeViewBinder<OverrideNode> {

        private final View root;
        private final TextView text;
        private final ImageView icon;
        private final ImageView arrow;

        private final TreeView.OnTreeNodeClickListener<OverrideNode> listener;

        public OverrideNodeViewBinder(View itemView,
                                      TreeView.OnTreeNodeClickListener<OverrideNode> listener) {
            super(itemView);

            root = itemView;
            text = itemView.findViewById(R.id.override_node_title);
            arrow = itemView.findViewById(R.id.arrow);
            icon = itemView.findViewById(R.id.icon);

            this.listener = listener;
        }

        @Override
        public void bindView(TreeNode<OverrideNode> treeNode) {
            DisplayMetrics displayMetrics =
                    ApplicationProvider.getApplicationContext().getResources().getDisplayMetrics();
            int startMargin = treeNode.getLevel() *
                    Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15,displayMetrics));
            ((ViewGroup.MarginLayoutParams) root.getLayoutParams()).setMarginStart(startMargin);

            OverrideNode value = treeNode.getValue();
            MethodPtr ptr = value.getMethodPtr();
            if (value.isMethod()) {
                String methodLabel = CompletionItemFactory.getMethodLabel(ptr.method,
                        (ExecutableType) ptr.method.asType());
                methodLabel += ":" + PrintHelper.printType(ptr.method.getReturnType());
                text.setText(methodLabel);
                icon.setImageDrawable(new CircleDrawable(DrawableKind.Method, true));
                arrow.setImageDrawable(null);
            } else {
                text.setText(ptr.className);
                icon.setImageDrawable(new CircleDrawable(DrawableKind.Class, true));
                arrow.setVisibility(View.VISIBLE);
            }

            arrow.setRotation(treeNode.isExpanded() ? 0 : 270);
        }

        @Override
        public void onNodeToggled(TreeNode<OverrideNode> treeNode, boolean expand) {
            super.onNodeToggled(treeNode, expand);

            if (expand) {
                arrow.setRotation(270);
                arrow.animate()
                        .rotationBy(90)
                        .setDuration(150L)
                        .start();
            } else {
                arrow.setRotation(0);
                arrow.animate()
                        .rotationBy(-90)
                        .setDuration(150L)
                        .start();
            }

            listener.onTreeNodeClicked(treeNode, expand);
        }

        @Override
        public boolean onNodeLongClicked(View view, TreeNode<OverrideNode> treeNode,
                                         boolean expanded) {
            return super.onNodeLongClicked(view, treeNode, expanded);
        }
    }

}
