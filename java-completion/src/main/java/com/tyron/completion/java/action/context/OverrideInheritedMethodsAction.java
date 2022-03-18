package com.tyron.completion.java.action.context;

import static com.tyron.completion.java.util.DiagnosticUtil.MethodPtr;

import android.app.Activity;
import android.app.ProgressDialog;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.util.AndroidUtilities;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.drawable.CircleDrawable;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.rewrite.OverrideInheritedMethod;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.java.util.PrintHelper;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.progress.ProgressIndicator;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Editor;
import com.tyron.ui.treeview.TreeNode;
import com.tyron.ui.treeview.TreeView;
import com.tyron.ui.treeview.base.BaseNodeViewBinder;
import com.tyron.ui.treeview.base.BaseNodeViewFactory;

import org.checkerframework.checker.nullness.qual.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.util.Elements;
import com.sun.source.tree.ClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        presentation.setText(event.getDataContext()
                                     .getString(
                                             R.string.menu_quickfix_override_inherited_methods_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Activity activity = e.getRequiredData(CommonDataKeys.ACTIVITY);
        File file = e.getRequiredData(CommonDataKeys.FILE);
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        JavaCompilerService compiler = e.getRequiredData(CommonJavaContextKeys.COMPILER);
        TreePath currentPath = e.getRequiredData(CommonJavaContextKeys.CURRENT_PATH);

        Module module = project.getModule(file);
        if (module == null) {
            AndroidUtilities.showSimpleAlert(e.getDataContext(), "Error",
                                             "The file is not part of any modules.");
            return;
        }

        FileManager fileManager = module.getFileManager();
        Optional<CharSequence> fileContent = fileManager.getFileContent(file);
        if (!fileManager.isOpened(file) || !fileContent.isPresent()) {
            AndroidUtilities.showSimpleAlert(e.getDataContext(), "Error",
                                             "The file is not currently opened in any editors.");
            return;
        }

        SourceFileObject sourceFileObject =
                new SourceFileObject(file.toPath(), (JavaModule) module, Instant.now());
        ListenableFuture<List<MethodPtr>> future = ProgressManager.getInstance()
                .computeNonCancelableAsync(() -> {
                    List<MethodPtr> pointers =
                            performInternal(compiler, sourceFileObject, currentPath);
                    Collections.reverse(pointers);
                    return Futures.immediateFuture(pointers);
                });


        ProgressDialog dialog = new ProgressDialog(e.getDataContext());
        Runnable showLoadingRunnable = dialog::show;
        // show a progress bar if task is still running after 2 seconds
        ProgressManager.getInstance()
                .runLater(showLoadingRunnable, 2000);

        Futures.addCallback(future, new FutureCallback<List<MethodPtr>>() {
            @Override
            public void onSuccess(@Nullable List<MethodPtr> pointers) {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                if (pointers == null) {
                    return;
                }
                OverrideInheritedMethodsAction.this.onSuccess(pointers, showLoadingRunnable, e,
                                                              sourceFileObject, file, editor,
                                                              compiler);
            }

            @Override
            public void onFailure(@NonNull Throwable t) {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                dialog.dismiss();
                ProgressManager.getInstance()
                        .cancelRunLater(showLoadingRunnable);
                AndroidUtilities.showSimpleAlert(e.getDataContext(), "Error", t.getMessage());
            }
        }, ContextCompat.getMainExecutor(e.getDataContext()));
    }

    private void onSuccess(@NonNull List<MethodPtr> pointers,
                           Runnable showLoadingRunnable,
                           @NonNull AnActionEvent e,
                           SourceFileObject sourceFileObject,
                           File file,
                           Editor editor,
                           JavaCompilerService compiler) {
        ProgressManager.getInstance()
                .cancelRunLater(showLoadingRunnable);

        TreeView<OverrideNode> treeView =
                new TreeView<>(e.getDataContext(), buildTreeNode(pointers));
        treeView.getView()
                .setPaddingRelative(0, AndroidUtilities.dp(8), 0, 0);

        OverrideNodeViewFactory factory = new OverrideNodeViewFactory();
        treeView.setAdapter(factory);

        AlertDialog dialog = new MaterialAlertDialogBuilder(e.getDataContext()).setTitle(
                R.string.menu_quickfix_implement_abstract_methods_title)
                .setView(treeView.getView())
                .setNegativeButton(android.R.string.cancel, null)
                .show();

        factory.setOnTreeNodeClickListener((node, expand) -> {
            if (node.isLeaf()) {
                dialog.dismiss();

                OverrideNode value = node.getValue();
                MethodPtr ptr = value.getMethodPtr();
                JavaRewrite rewrite = new OverrideInheritedMethod(ptr.className, ptr.methodName,
                                                                  ptr.erasedParameterTypes,
                                                                  sourceFileObject, editor.getCaret()
                                                                          .getStart());

                RewriteUtil.performRewrite(editor, file, compiler, rewrite);
            }
        });
    }

    @WorkerThread
    private List<MethodPtr> performInternal(JavaCompilerService compiler,
                                            SourceFileObject file,
                                            TreePath currentPath) {
        CompilerContainer container = compiler.compile(Collections.singletonList(file));
        return container.get(task -> {
            Trees trees = Trees.instance(task.task);
            Element classElement = trees.getElement(currentPath);
            Elements elements = task.task.getElements();
            List<MethodPtr> methodPtrs = new ArrayList<>();
            for (Element member : elements.getAllMembers((TypeElement) classElement)) {
                if (member.getModifiers()
                        .contains(Modifier.FINAL)) {
                    continue;
                }
                if (member.getModifiers()
                        .contains(Modifier.STATIC)) {
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
                OverrideNode classNode = new OverrideNode(methods.iterator()
                                                                  .next(), false);
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
            DisplayMetrics displayMetrics = ApplicationProvider.getApplicationContext()
                    .getResources()
                    .getDisplayMetrics();
            int startMargin = treeNode.getLevel() *
                              Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15,
                                                                   displayMetrics));
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
        public boolean onNodeLongClicked(View view,
                                         TreeNode<OverrideNode> treeNode,
                                         boolean expanded) {
            return super.onNodeLongClicked(view, treeNode, expanded);
        }
    }

}
