package com.tyron.code.action;

import android.util.Log;

import com.tyron.completion.CompileTask;
import com.tyron.completion.CompilerProvider;
import com.tyron.completion.FindTypeDeclarationAt;
import com.tyron.code.model.CodeAction;
import com.tyron.code.model.CodeActionList;
import com.tyron.completion.model.Position;
import com.tyron.completion.model.Range;
import com.tyron.completion.model.TextEdit;
import com.tyron.code.rewrite.AddImport;
import com.tyron.code.rewrite.ImplementAbstractMethods;
import com.tyron.code.rewrite.OverrideInheritedMethod;
import com.tyron.code.rewrite.Rewrite;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.util.Elements;
import org.openjdk.javax.lang.model.util.Types;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.LineMap;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.util.JCDiagnostic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CodeActionProvider {

    private final CompilerProvider mCompiler;

    public CodeActionProvider(CompilerProvider compiler) {
        mCompiler = compiler;
    }

    public List<CodeActionList> codeActionsForCursor(Path file, long cursor) {

        if (true) {
            Lint lint;
            return Collections.emptyList();
        }
        List<CodeActionList> codeActionList = new ArrayList<>();

        List<IAction> applicableActions = new ArrayList<>();
        Diagnostic<? extends JavaFileObject> diagnostic;
        TreeMap<String, Rewrite> overrideMethods = new TreeMap<>();
        try (CompileTask task = mCompiler.compile(file)) {
            diagnostic = getDiagnostic(task, cursor);
            overrideMethods.putAll(getOverrideInheritedMethods(task, file, cursor));
            TreePath currentPath = new FindCurrentPath(task.task).scan(task.root(), cursor);
            if (currentPath != null) {
                Log.d(null, "Current path: " + currentPath.getLeaf().getKind().toString());
                for (IAction action : getActions()) {
                    if (action.isApplicable(currentPath, task)) {
                        applicableActions.add(action);
                    }
                }
            }
        }
        if (diagnostic != null) {
            codeActionList.add(getDiagnosticActions(file, diagnostic));
        }

        CodeActionList overrideAction = new CodeActionList();
        overrideAction.setTitle("Override inherited methods");
        overrideAction.setActions(getActionsFromRewrites(overrideMethods));
        codeActionList.add(overrideAction);


        for (IAction action : applicableActions) {
           codeActionList.add(action.get(null));
        }

        return codeActionList;
    }

    private List<CodeAction> getActionsFromRewrites(Map<String, Rewrite> rewrites) {
        List<CodeAction> actions = new ArrayList<>();
        for (Map.Entry<String, Rewrite> entry : rewrites.entrySet()) {
            actions.addAll(createQuickFix(entry.getKey(), entry.getValue()));
        }
        return actions;
    }

    private CodeActionList getDiagnosticActions(Path file, Diagnostic<? extends JavaFileObject> diagnostic) {
        CodeActionList list = new CodeActionList();
        list.setTitle("Quick fixes");

        TreeMap<String, Rewrite> rewrites = new TreeMap<>(quickFixes(file, diagnostic));

        List<CodeAction> actions = new ArrayList<>();
        for (Map.Entry<String, Rewrite> entry : rewrites.entrySet()) {
            actions.addAll(createQuickFix(entry.getKey(), entry.getValue()));
        }
        list.setActions(actions);

        return list;
    }

    /**
     * Gets the diagnostics of the current compile task
     *
     * @param task the current compile task where the diagnostic is retrieved
     * @param cursor the current cursor position
     * @return null if no diagnostic is found
     */
    private Diagnostic<? extends JavaFileObject> getDiagnostic(CompileTask task, long cursor) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : task.diagnostics) {
            if (diagnostic.getStartPosition() <= cursor && cursor < diagnostic.getEndPosition()) {
                return diagnostic;
            }
        }
        return null;
    }

    private Map<String, Rewrite> getOverrideInheritedMethods(CompileTask task, Path file, long cursor) {
        TreeMap<String, Rewrite> rewrites = new TreeMap<>(overrideInheritedMethods(task, file, cursor));

        List<CodeAction> actions = new ArrayList<>();
        for (String title : rewrites.keySet()) {
            actions.addAll(createQuickFix(title, rewrites.get(title)));
        }
       return rewrites;
    }

    private Map<String, Rewrite> overrideInheritedMethods(CompileTask task, Path file, long cursor) {
        if (!isBlankLine(task.root(), cursor)) {
            return Collections.emptyMap();
        }
        if (isInMethod(task, cursor)) {
            return Collections.emptyMap();
        }
        MethodTree methodTree = new FindMethodDeclarationAt(task.task).scan(task.root(), cursor);
        if (methodTree != null) {
            return Collections.emptyMap();
        }
        TreeMap<String, Rewrite> actions = new TreeMap<>();
        Trees trees = Trees.instance(task.task);
        ClassTree classTree = new FindTypeDeclarationAt(task.task).scan(task.root(), cursor);
        if (classTree == null) {
            return Collections.emptyMap();
        }
        TreePath classPath = trees.getPath(task.root(), classTree);
        Elements elements = task.task.getElements();
        TypeElement classElement = (TypeElement) trees.getElement(classPath);
        for (Element member : elements.getAllMembers(classElement)) {
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
            MethodPtr ptr = new MethodPtr(task.task, method);
            Rewrite rewrite = new OverrideInheritedMethod(
                    ptr.className, ptr.methodName, ptr.erasedParameterTypes, file, (int) cursor);
            String title = "Override " + method.getSimpleName() + " from " + ptr.className;
            actions.put(title, rewrite);
        }

        return actions;
    }

    private boolean isInMethod(CompileTask task, long cursor) {
        MethodTree method = new FindMethodDeclarationAt(task.task).scan(task.root(), cursor);
        return method != null;
    }

    private boolean isBlankLine(CompilationUnitTree root, long cursor) {
        LineMap lines = root.getLineMap();
        long line = lines.getLineNumber(cursor);
        long start = lines.getStartPosition(line);
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (long i = start; i < cursor; i++) {
            if (!Character.isWhitespace(contents.charAt((int) i))) {
                return false;
            }
        }
        return true;
    }

    public Map<String, Rewrite> quickFixes(Path file, Diagnostic<? extends JavaFileObject> d) {
        Log.d(null, "Class name: " + d.getClass().toString());
        if (d instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper) {
            JCDiagnostic diagnostic = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) d).d;
            Log.d(null, "code: " + diagnostic.getCode());
            switch (d.getCode()) {
                case "compiler.err.does.not.override.abstract":
                    String missingAbstracts = diagnostic.getArgs()[0].toString();
                    Log.d(null, "simple abstract name: " + missingAbstracts);
                    Rewrite implementAbstracts = new ImplementAbstractMethods(missingAbstracts);
                    return Collections.singletonMap("Implement abstract methods", implementAbstracts);
                case "compiler.err.cant.resolve":
                case "compiler.err.cant.resolve.location":
                    CharSequence simpleName = diagnostic.getArgs()[1].toString();
                    TreeMap<String, Rewrite> allImports = new TreeMap<>();
                    Log.d(null, "simple name: " + simpleName);
                    for (String qualifiedName : mCompiler.publicTopLevelTypes()) {
                        if (qualifiedName.endsWith("." + simpleName)) {
                            String title = "Import " + qualifiedName;
                            Rewrite addImport = new AddImport(file.toFile(), qualifiedName);
                            allImports.put(title, addImport);
                        }
                    }
                    return allImports;
            }

        }
        return Collections.emptyMap();
    }

    private List<CodeAction> createQuickFix(String title, Rewrite rewrite) {
        Map<Path, TextEdit[]> edits = rewrite.rewrite(mCompiler);
        if (edits == Rewrite.CANCELLED) {
            return Collections.emptyList();
        }
        CodeAction action = new CodeAction();
        action.setTitle(title);
        Map<Path, List<TextEdit>> textEdits = new HashMap<>();
        for (Path file : edits.keySet()) {
            if (file == null) {
                continue;
            }
            TextEdit[] value = edits.get(file);
            if (value == null) {
                continue;
            }
            textEdits.put(file, Arrays.asList(value));
        }
        action.setEdits(textEdits);

        return Collections.singletonList(action);
    }

    private String findClass(CompileTask task, Range range) {
        ClassTree type = findClassTree(task, range);
        if (type == null) return null;
        return qualifiedName(task, type);
    }

    private ClassTree findClassTree(CompileTask task, Range range) {
        long position = task.root().getLineMap().getPosition(range.start.line , range.start.column);
        return new FindTypeDeclarationAt(task.task).scan(task.root(), position);
    }

    private String qualifiedName(CompileTask task, ClassTree tree) {
        Trees trees = Trees.instance(task.task);
        TreePath path = trees.getPath(task.root(), tree);
        TypeElement type = (TypeElement) trees.getElement(path);
        return type.getQualifiedName().toString();
    }

    private boolean hasConstructor(CompileTask task, ClassTree type) {
        for (Tree member : type.getMembers()) {
            if (member instanceof MethodTree) {
                MethodTree method = (MethodTree) member;
                if (isConstructor(task, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isConstructor(CompileTask task, MethodTree method) {
        return method.getName().contentEquals("<init>") && !synthetic(task, method);
    }

    private boolean synthetic(CompileTask task, MethodTree method) {
        return Trees.instance(task.task).getSourcePositions().getStartPosition(task.root(), method) != -1;
    }

    private int findPosition(CompileTask task, Position position) {
        LineMap lines = task.root().getLineMap();
        return (int) lines.getPosition(position.line + 1, position.column + 1);
    }

    private int findLine(CompileTask task, long position) {
        LineMap lines = task.root().getLineMap();
        return (int) lines.getLineNumber(position);
    }

    private int findColumn(CompileTask task, long position) {
        LineMap lines = task.root().getLineMap();
        return (int) lines.getColumnNumber(position);
    }
    private Range getRange(CompileTask task, Diagnostic<? extends JavaFileObject> diagnostic) {
        int startLine = findLine(task, diagnostic.getStartPosition());
        int startColumn = findColumn(task, diagnostic.getStartPosition());
        int endLine = findLine(task, diagnostic.getEndPosition());
        int endColumn = findColumn(task, diagnostic.getEndPosition());

        return new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
    }

    private static class MethodPtr {
        String className, methodName;
        String[] erasedParameterTypes;

        MethodPtr(JavacTask task, ExecutableElement method) {
            Types types = task.getTypes();
            TypeElement parent = (TypeElement) method.getEnclosingElement();
            className = parent.getQualifiedName().toString();
            methodName = method.getSimpleName().toString();
            erasedParameterTypes = new String[method.getParameters().size()];
            for (int i = 0; i < erasedParameterTypes.length; i++) {
                VariableElement param = method.getParameters().get(i);
                TypeMirror type = param.asType();
                TypeMirror erased = types.erasure(type);
                erasedParameterTypes[i] = erased.toString();
            }
        }
    }

    private CharSequence extractRange(CompileTask task, Range range) {
        CharSequence contents;
        try {
            contents = task.root().getSourceFile().getCharContent(true);
        } catch (IOException e) {
            return "";
        }

        int start = (int) task.root().getLineMap().getPosition(range.start.line, range.start.column);
        int end = (int) task.root().getLineMap().getPosition(range.end.line, range.end.column);

        CharSequence charSequence = contents.subSequence(start, end);
        return charSequence;
    }


    private List<IAction> getActions() {
        return Arrays.asList(
                new ConvertToAnonymousAction(),
                new ConvertToLambdaAction()
        );
    }

}
