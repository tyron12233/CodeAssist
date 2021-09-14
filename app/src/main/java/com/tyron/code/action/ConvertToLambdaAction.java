package com.tyron.code.action;

import android.util.Log;

import com.tyron.code.completion.CompileTask;
import com.tyron.code.completion.provider.ScopeHelper;
import com.tyron.code.model.CodeAction;
import com.tyron.code.model.CodeActionList;
import com.tyron.code.service.CompilerService;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.ExecutableType;
import org.openjdk.javax.lang.model.type.TypeKind;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.source.tree.LambdaExpressionTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.code.Symbol;
import org.openjdk.tools.javac.code.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConvertToLambdaAction extends IAction {

    //private final CompilerService mCompiler;
    private Element mMethod;


    @Override
    public boolean isApplicable(TreePath tree, CompileTask task) {
        Log.d(null, "called");
        if (tree.getLeaf().getKind() == Tree.Kind.NEW_CLASS) {
            Element element = Trees.instance(task.task).getElement(tree.getParentPath());
            if (element instanceof VariableElement) {
                VariableElement variableElement = (VariableElement) element;
                DeclaredType declaredType = (DeclaredType) variableElement.asType();
                Element classElement = declaredType.asElement();
                if (classElement.getKind().isInterface()) {
                    List<? extends Element> elements = classElement.getEnclosedElements();
                    Log.d(null, "elements: " + elements.toString());
                    List<Element> methods = new ArrayList<>();
                    for (Element inner : elements) {
                        if (inner.getKind() != ElementKind.METHOD) {
                            continue;
                        }
                        if (inner.getModifiers().contains(Modifier.STATIC)) {
                            continue;
                        }
                        if (inner.getModifiers().contains(Modifier.DEFAULT)) {
                            continue;
                        }
                        methods.add(inner);
                    }

                    if (methods.size() == 1) {
                        mMethod = methods.iterator().next();
                        Log.d(null, mMethod.toString());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public CodeActionList get(CompileTask task) {
        CodeActionList list = new CodeActionList();
        list.setTitle("Convert to lambda");

        CodeAction action = new CodeAction();
        action.setTitle("Convert to anonymous class");
        action.setEdits(Collections.emptyMap());
        list.setActions(Collections.singletonList(action));

        return list;
    }

    Runnable  runnable = () -> {

    };
}
