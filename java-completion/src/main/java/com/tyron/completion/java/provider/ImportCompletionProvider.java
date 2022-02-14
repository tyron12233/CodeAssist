package com.tyron.completion.java.provider;

import static com.tyron.completion.java.provider.MemberSelectCompletionProvider.putMethod;
import static com.tyron.completion.java.util.CompletionItemFactory.importClassItem;
import static com.tyron.completion.java.util.CompletionItemFactory.item;
import static com.tyron.completion.java.util.CompletionItemFactory.method;
import static com.tyron.completion.java.util.CompletionItemFactory.packageItem;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.Name;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

public class ImportCompletionProvider extends BaseCompletionProvider {

    public ImportCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath treePath, String path, boolean endsWithParen) {
        checkCanceled();

        Set<String> names = new HashSet<>();
        for (String className : getCompiler().publicTopLevelTypes()) {
            if (className.startsWith(path)) {
                int start = path.lastIndexOf('.');
                int end = className.indexOf('.', path.length());
                if (end == -1) end = className.length();
                String segment = className.substring(start + 1, end);
                if (names.contains(segment)) continue;
                names.add(segment);
                boolean isClass = className.endsWith(segment);
                if (isClass) {
                    builder.addItem(importClassItem(className));
                } else {
                    builder.addItem(packageItem(segment));
                }
            }
        }
    }

    public List<CompletionItem> addAnonymous(CompileTask task, TreePath path, String partial) {
        checkCanceled();

        List<CompletionItem> items = new ArrayList<>();

        if (!(path.getLeaf() instanceof NewClassTree)) {
            return items;
        }

        if (path.getParentPath().getParentPath().getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
            Trees trees = Trees.instance(task.task);
            MethodInvocationTree method =
                    (MethodInvocationTree) path.getParentPath().getParentPath().getLeaf();
            Element element = trees.getElement(path.getParentPath().getParentPath());

            if (element instanceof ExecutableElement) {
                ExecutableElement executable = (ExecutableElement) element;

                int argumentToComplete = 0;
                for (int i = 0; i < method.getArguments().size(); i++) {
                    ExpressionTree exp = method.getArguments().get(i);
                    if (exp.toString().equals(partial) || exp.toString().equals("new " + partial)) {
                        argumentToComplete = i;
                    }
                }

                VariableElement var = executable.getParameters().get(argumentToComplete);
                if (var.asType() instanceof DeclaredType) {
                    DeclaredType type = (DeclaredType) var.asType();
                    Element classElement = type.asElement();

                    if (StringSearch.matchesPartialName(classElement.getSimpleName().toString(),
                            partial)) {
                        CompletionItem item = new CompletionItem();

                        StringBuilder sb = new StringBuilder();
                        if (classElement instanceof TypeElement) {
                            TypeElement typeElement = (TypeElement) classElement;
                            // import the class
                            item.action = CompletionItem.Kind.IMPORT;
                            item.data = typeElement.getQualifiedName().toString();
                            item.iconKind = DrawableKind.Interface;
                            item.label = classElement.getSimpleName().toString() + " {...}";
                            item.commitText = "" + classElement.getSimpleName() + "() {\n" + "\t" +
                                    "// TODO\n" + "}";
                            item.cursorOffset = item.commitText.length();
                            item.detail = "";
                        }
                        items.add(item);
                    }
                }
            }
        }
        return items;
    }

    public static void addStaticImports(CompileTask task, CompilationUnitTree root, String partial,
                                  boolean endsWithParen, CompletionList.Builder list) {
        checkCanceled();

        Trees trees = Trees.instance(task.task);
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (ImportTree i : root.getImports()) {
            if (!i.isStatic()) continue;
            MemberSelectTree id = (MemberSelectTree) i.getQualifiedIdentifier();
            if (!importMatchesPartial(id.getIdentifier(), partial)) continue;
            TreePath path = trees.getPath(root, id.getExpression());
            TypeElement type = (TypeElement) trees.getElement(path);
            for (Element member : type.getEnclosedElements()) {
                if (!member.getModifiers().contains(Modifier.STATIC)) continue;
                if (!memberMatchesImport(id.getIdentifier(), member)) continue;
                if (FuzzySearch.partialRatio(String.valueOf(member.getSimpleName()), partial) < 70) continue;
                if (member.getKind() == ElementKind.METHOD) {
                    methods.clear();
                    putMethod((ExecutableElement) member, methods);
                    for (List<ExecutableElement> overloads : methods.values()) {
                        for (CompletionItem item : method(task, overloads, endsWithParen, false,
                                                          (DeclaredType) type.asType())) {
                            item.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                            list.addItem(item);
                        }
                    }
                } else {
                    CompletionItem item = item(member);
                    item.setSortText(JavaSortCategory.ACCESSIBLE_SYMBOL.toString());
                    list.addItem(item);
                }
            }
        }
    }


    private static boolean importMatchesPartial(Name staticImport, String partial) {
        return staticImport.contentEquals("*") || StringSearch.matchesPartialName(staticImport,
                partial);
    }

    private static boolean memberMatchesImport(Name staticImport, Element member) {
        return staticImport.contentEquals("*") || staticImport.contentEquals(member.getSimpleName());
    }
}
