package com.tyron.completion.java.action.context;

import android.app.AlertDialog;
import android.view.MenuItem;

import androidx.annotation.NonNull;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.api.Action;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;
import com.tyron.completion.java.rewrite.OverrideInheritedMethod;
import com.tyron.completion.java.rewrite.Rewrite;
import com.tyron.completion.java.util.DiagnosticUtil;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.Map;
import java.util.TreeMap;

public class OverrideInheritedMethodsAction extends ActionProvider {

    @Override
    public boolean isApplicable(ActionContext context, @NonNull TreePath currentPath) {
        return currentPath.getLeaf() instanceof ClassTree;
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        Tree leaf = context.getCurrentPath().getLeaf();
        if (!(leaf instanceof ClassTree)) {
            return;
        }

        String title =
                context.getContext().getString(R.string.menu_quickfix_override_inherited_methods_title);
        MenuItem menuItem = context.addMenu("overrideMethods", title);
        menuItem.setOnMenuItemClickListener(item -> {
            perform(context);
            return true;
        });
    }

    private void perform(ActionContext context) {
        CompilerContainer container = context.getCompiler().compile(context.getCurrentFile());
        container.run(task -> {
            Trees trees = Trees.instance(task.task);
            Element classElement = trees.getElement(context.getCurrentPath());
            Elements elements = task.task.getElements();
            Map<String, Rewrite> rewriteMap = new TreeMap<>();
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
                if (methodSource.getQualifiedName().contentEquals("java.lang.Object")) {
                    continue;
                }
                if (methodSource.equals(classElement)) {
                    continue;
                }
                DiagnosticUtil.MethodPtr ptr = new DiagnosticUtil.MethodPtr(task.task, method);
                Rewrite rewrite = new OverrideInheritedMethod(ptr.className, ptr.methodName,
                        ptr.erasedParameterTypes, context.getCurrentFile(), context.getCursor());
                String title = "Override " + method.getSimpleName() + " from " + ptr.className;
                rewriteMap.put(title, rewrite);
            }

            String[] strings = rewriteMap.keySet().toArray(new String[0]);

            new AlertDialog.Builder(context.getContext()).setTitle("Override inherited methods").setItems(strings, (d, w) -> {
                Rewrite rewrite = rewriteMap.get(strings[w]);
                context.performAction(new Action(rewrite));
            }).setNegativeButton(android.R.string.cancel, null).show();
        });
    }
}
