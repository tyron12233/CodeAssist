package com.tyron.completion.java.action.context;

import android.app.AlertDialog;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.completion.java.rewrite.OverrideInheritedMethod;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.editor.Editor;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

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
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        File file = e.getData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getData(CommonJavaContextKeys.COMPILER);
        TreePath currentPath = e.getData(CommonJavaContextKeys.CURRENT_PATH);

        Map<String, JavaRewrite> rewriteMap = performInternal(compiler, file.toPath(),
                currentPath, editor.getCaret().getStart());

        String[] titles = rewriteMap.keySet().toArray(new String[0]);

        new AlertDialog.Builder(e.getDataContext())
                .setTitle(R.string.menu_quickfix_implement_abstract_methods_title)
                .setItems(titles, (d, which) -> {
                    JavaRewrite rewrite = rewriteMap.get(titles[which]);
                    RewriteUtil.performRewrite(editor, file, compiler, rewrite);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Map<String, JavaRewrite> performInternal(JavaCompilerService compiler,
                                                     Path file,
                                                     TreePath currentPath,
                                                     int index) {
        CompilerContainer container = compiler.compile(file);
        return container.get(task -> {
            Trees trees = Trees.instance(task.task);
            Element classElement = trees.getElement(currentPath);
            Elements elements = task.task.getElements();
            Map<String, JavaRewrite> rewriteMap = new TreeMap<>();
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
                JavaRewrite rewrite = new OverrideInheritedMethod(ptr.className, ptr.methodName,
                        ptr.erasedParameterTypes, file, index);
                String title = "Override " + method.getSimpleName() + " from " + ptr.className;
                rewriteMap.put(title, rewrite);
            }

            return rewriteMap;
        });
    }
}
