package com.tyron.code.completion.provider;

import android.util.Log;

import com.tyron.code.completion.CompileTask;
import com.tyron.code.completion.CompilerProvider;
import com.tyron.code.completion.CustomActions;
import com.tyron.code.completion.ParseTask;
import com.tyron.code.completion.SourceFileObject;
import com.tyron.code.model.CompletionItem;
import com.tyron.code.model.CompletionList;
import com.tyron.code.ui.editor.drawable.CircleDrawable;
import com.tyron.code.util.StringSearch;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import org.openjdk.javax.lang.model.element.Element;
import org.openjdk.javax.lang.model.element.ElementKind;
import org.openjdk.javax.lang.model.element.ExecutableElement;
import org.openjdk.javax.lang.model.element.Modifier;
import org.openjdk.javax.lang.model.element.Name;
import org.openjdk.javax.lang.model.element.TypeElement;
import org.openjdk.javax.lang.model.element.VariableElement;
import org.openjdk.javax.lang.model.type.ArrayType;
import org.openjdk.javax.lang.model.type.DeclaredType;
import org.openjdk.javax.lang.model.type.TypeMirror;
import org.openjdk.javax.lang.model.type.TypeVariable;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ExpressionTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.MemberReferenceTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.MethodInvocationTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.NewClassTree;
import org.openjdk.source.tree.Scope;
import org.openjdk.source.tree.SwitchTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.tree.JCTree;

/**
 * Main entry point for getting completions
 */
@SuppressWarnings("NewApi")
public class CompletionProvider {

	private static final String TAG = CompletionProvider.class.getSimpleName();

    private static final String[] TOP_LEVEL_KEYWORDS = {
        "package",
        "import",
        "public",
        "private",
        "protected",
        "abstract",
        "class",
        "interface",
        "@interface",
        "extends",
        "implements",
    };

    private static final String[] CLASS_BODY_KEYWORDS = {
        "public",
        "private",
        "protected",
        "static",
        "final",
        "native",
        "synchronized",
        "abstract",
        "default",
        "class",
        "interface",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    private static final String[] METHOD_BODY_KEYWORDS = {
        "new",
        "assert",
        "try",
        "catch",
        "finally",
        "throw",
        "return",
        "break",
        "case",
        "continue",
        "default",
        "do",
        "while",
        "for",
        "switch",
        "if",
        "else",
        "instanceof",
        "final",
        "class",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    //private final JavaParser parser;
	private final CompilerProvider compiler;

    private static final int MAX_COMPLETION_ITEMS = 50;

    public CompletionProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

	public CompletionList complete(File file, long index) {
		ParseTask task = compiler.parse(Paths.get(file.getAbsolutePath()));

		StringBuilder contents;
		try {
            contents = new PruneMethodBodies(task.task).scan(task.root, index);
            int end = endOfLine(contents, (int) index);
            contents.insert(end, ';');
        } catch (IndexOutOfBoundsException ignore) {
            return new CompletionList();
        }
		CompletionList list = compileAndComplete(file, contents.toString(), index);
		//addTopLevelSnippets(task, list);
		return list;
	}

	public CompletionList compileAndComplete(File file, String contents, long cursor) {
		Instant start = Instant.now();
		SourceFileObject source = new SourceFileObject(file.toPath(), contents, start);
		String partial = partialIdentifier(contents, (int) cursor);
		boolean endsWithParen = endsWithParen(contents, (int) cursor);
		//noinspection
		try (CompileTask task = compiler.compile(List.of(source))) {
			Log.d(TAG, "Compiled in: " + Duration.between(start, Instant.now()).toMillis() + "ms");
			TreePath path = new FindCompletionsAt(task.task).scan(task.root(), cursor);
			switch (path.getLeaf().getKind()) {
				case IDENTIFIER:             
					return completeIdentifier(task, path, partial, endsWithParen);
				case MEMBER_SELECT:              
					return completeMemberSelect(task, path, partial, endsWithParen);
				case MEMBER_REFERENCE:
					return completeMemberReference(task, path, partial);
				case CASE:
					return completeSwitchConstant(task, path, partial);
				case IMPORT:
					return completeImport(qualifiedPartialIdentifier(contents.toString(), (int) cursor));
				default:
					CompletionList list = new CompletionList();
					addKeywords(path, partial, list);
					return list;
			}
		}
	}

	/**
	 * Use compileAndComplete instead for incremental compilation
	 */
	@Deprecated
    public CompletionList complete(CompilationUnitTree root, int index) {
        return null;
    }

	private void addTopLevelSnippets(ParseTask task, CompletionList list) {
        Path file = Paths.get(task.root.getSourceFile().toUri());
        if (!hasTypeDeclaration(task.root)) {
            list.items.add(classSnippet(file));
            if (task.root.getPackageName() == null) {
                list.items.add(packageSnippet(file));
            }
        }
    }

	private CompletionItem packageSnippet(Path file) {
        String name = "com.tyron.test";
        return snippetItem("package " + name, "package " + name + ";\n\n");
    }

    private CompletionItem classSnippet(Path file) {
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".java".length());
        return snippetItem("class " + name, "class " + name + " {\n    $0\n}");
    }

	private boolean hasTypeDeclaration(CompilationUnitTree root) {
        for (Tree tree : root.getTypeDecls()) {
            if (tree.getKind() != Tree.Kind.ERRONEOUS) {
                return true;
            }
        }
        return false;
    }

    public static int endOfLine(CharSequence contents, int cursor) {
        while (cursor < contents.length()) {
            char c = contents.charAt(cursor);
            if (c == '\r' || c == '\n') break;
            cursor++;
        }
        return cursor;
    }

    private String partialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    private String qualifiedPartialIdentifier(String contents, int end) {
        int start = end;
        while (start > 0 && isQualifiedIdentifierChar(contents.charAt(start - 1))) {
            start--;
        }
        return contents.substring(start, end);
    }

    public static boolean isQualifiedIdentifierChar(char c) {
        return c == '.' || Character.isJavaIdentifierPart(c);
    }


    private boolean endsWithParen(String contents, int cursor) {
        for (int i = cursor; i < contents.length(); i++) {
            if (!Character.isJavaIdentifierPart(contents.charAt(i))) {
                return contents.charAt(i) == '(';
            }
        }
        return false;
    }

    private void addKeywords(TreePath path, String partial, CompletionList list) {
        Tree level = findKeywordLevel(path);
        String[] keywords = {};
        if (level instanceof CompilationUnitTree) {
            keywords = TOP_LEVEL_KEYWORDS;
        } else if (level instanceof ClassTree) {
            keywords = CLASS_BODY_KEYWORDS;
        } else if (level instanceof MethodTree) {
            keywords = METHOD_BODY_KEYWORDS;
        }
        for (String k : keywords) {
            if (StringSearch.matchesPartialName(k, partial)) {
                list.items.add(keyword(k));
            }
        }
    }

    private Tree findKeywordLevel(TreePath path) {
        while (path != null) {
            if (path.getLeaf() instanceof CompilationUnitTree
                || path.getLeaf() instanceof ClassTree
                || path.getLeaf() instanceof MethodTree) {
                return path.getLeaf();
            }
            path = path.getParentPath();
        }
        throw new RuntimeException("empty path");
    }
    private boolean isAnnotationTree(TreePath path) {
		if (path == null) {
			return false;
		}
		
		if (path.getLeaf() instanceof JCTree.JCIdent) {
		    if (path.getParentPath().getLeaf() instanceof JCTree.JCAnnotation) {
		        return true;
            }
        }

		return false;
	}
    private CompletionList completeIdentifier(CompileTask task, TreePath path, final String partial, boolean endsWithParen) {
        CompletionList list = new CompletionList();
        list.items = completeUsingScope(task, path, partial, endsWithParen);
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), partial, list);
        }
        addKeywords(path, partial, list);
		if (isAnnotationTree(path)) {
			if (StringSearch.matchesPartialName("Override", partial)) {
                CustomActions.addOverrideItem(list);
            }
		}
        return list;
    }

    private CompletionList completeMemberSelect(CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        MemberSelectTree select = (MemberSelectTree) path.getLeaf();
        path = new TreePath(path, select.getExpression());
        Trees trees = Trees.instance(task.task);
        boolean isStatic = trees.getElement(path) instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);
        //  Log.d("Completion on MemberSelect", "type: " + type.getKind() + " " + type.getClass().getName());
        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope, (TypeVariable) type, isStatic, partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope, (DeclaredType) type, isStatic, partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeArrayMemberSelect(boolean isStatic) {
        if (isStatic) {
            return new CompletionList();
        } else {
            CompletionList list = new CompletionList();
            list.items.add(keyword("length"));
            return list;
        }
    }

    private CompletionList completeTypeVariableMemberSelect(CompileTask task, Scope scope, TypeVariable type, boolean isStatic, String partial, boolean endsWithParen) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(task, scope, (DeclaredType) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(task, scope, (TypeVariable) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberSelect(CompileTask task, Scope scope, DeclaredType type, boolean isStatic, String partial, boolean endsWithParen) {
        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();
        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial) && !partial.endsWith(".")) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            Log.d("DeclaredTypeCompletion", "member name: " + member.getSimpleName());
            if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.addAll(method(overloads, !endsWithParen));
        }
        if (isStatic) {
            list.add(keyword("class"));
        }
        if (isStatic && isEnclosingClass(type, scope)) {
            list.add(keyword("this"));
            list.add(keyword("super"));
        }

        CompletionList cl = new CompletionList();
        cl.items = list;
        return cl;
    }


    private List<CompletionItem> completeUsingScope(CompileTask task, TreePath path, final String partial, boolean endsWithParen) {
        Trees trees = Trees.instance(task.task);
        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        Scope scope = trees.getScope(path);

        Log.d("IDENTIFIER PATH", path.getParentPath().getLeaf().getKind().toString());

        Predicate<CharSequence> filter = p1 -> StringSearch.matchesPartialName(String.valueOf(p1), partial);

        if (path.getParentPath().getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
            list.addAll(addLambda(task, path.getParentPath(), partial));
        }

        if (path.getParentPath().getLeaf().getKind() == Tree.Kind.NEW_CLASS) {
            list.addAll(addAnonymous(task, path.getParentPath(), partial));
        }

        for (Element element : ScopeHelper.scopeMembers(task, scope, filter)) {
            if (element.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) element, methods);
            } else {
                list.add(item(element));
            }
        }

        for (List<ExecutableElement> overloads : methods.values()) {
            list.addAll(method(overloads, !endsWithParen));
        }
        return list;
    }

    public List<CompletionItem> addLambda(CompileTask task, TreePath path, String partial) {
        Trees trees = Trees.instance(task.task);
        List<CompletionItem> items = new ArrayList<>();
        MethodInvocationTree method = (MethodInvocationTree) path.getLeaf();
        Element element = trees.getElement(path);

        if (!(element instanceof ExecutableElement)) {
            return Collections.emptyList();
        }

        ExecutableElement executable = (ExecutableElement) element;

        int argumentToComplete= 0;
        for (int i = 0; i < method.getArguments().size(); i++) {
            ExpressionTree exp = method.getArguments().get(i);
            if (exp.toString().equals(partial) || exp.toString().equals("new " + partial)) {
                argumentToComplete = i;
            }
        }
        List<? extends VariableElement> variableElements = executable.getParameters();

        if (argumentToComplete > variableElements.size() - 1) {
            return Collections.emptyList();
        }

        VariableElement var = variableElements.get(argumentToComplete);

        if (var.asType() instanceof DeclaredType) {
            DeclaredType type = (DeclaredType) var.asType();
            Element classElement = type.asElement();

            if (classElement.getKind() == ElementKind.INTERFACE) {
                Map<String, List<ExecutableElement>> methods = new HashMap<>();

                for (Element enc : classElement.getEnclosedElements()) {
                    if (enc.getKind() == ElementKind.METHOD) {
                        if (enc.getModifiers().contains(Modifier.STATIC)) {
                            continue;
                        }
						if (enc.getModifiers().contains(Modifier.DEFAULT)) {
							continue;
						}
                        putMethod((ExecutableElement) enc, methods);
                    }
                }

                // this is a SAM Interface, suggest a lambda
                if (methods.values().size() == 1) {
                    ExecutableElement sam = methods.values().iterator().next().iterator().next();

                    CompletionItem item = new CompletionItem();

                    StringBuilder label = new StringBuilder();
                    for (VariableElement param : sam.getParameters()) {
                        label.append((label.length() == 0) ? "" : ", ").append(param.getSimpleName());
                    }

                    item.label = (sam.getParameters().size() == 1)
                            ? label + " -> "
                            : "(" + label + ")" + " -> ";
                    item.commitText = item.label;
                    item.detail = simpleName(sam.getReturnType().toString()).toString();
                    item.cursorOffset = item.label.length();
                    item.iconKind = CircleDrawable.Kind.Lambda;
                    items.add(item);
                } 
            }
        }

        return items;
    }

    public List<CompletionItem> addAnonymous(CompileTask task, TreePath path, String partial) {
        List<CompletionItem> items = new ArrayList<>();

		if (!(path.getLeaf() instanceof NewClassTree)) {
			return items;
		}

        if (path.getParentPath().getParentPath().getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
            Trees trees = Trees.instance(task.task);
            MethodInvocationTree method = (MethodInvocationTree) path.getParentPath().getParentPath().getLeaf();
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

					if (StringSearch.matchesPartialName(classElement.getSimpleName().toString(), partial)) {
                        CompletionItem item = new CompletionItem();
                        item.iconKind = CircleDrawable.Kind.Interface;
                        item.label = classElement.getSimpleName().toString() + " {...}";
                        item.commitText = "new " + classElement.getSimpleName() + "() {\n\t//TODO:\n}";
                        item.cursorOffset = item.commitText.length();
                        item.detail = "";

                        if (classElement instanceof TypeElement) {
                            // import the class
                            item.action = CompletionItem.Kind.IMPORT;
                            item.data = ((TypeElement) classElement).getQualifiedName().toString();
                        }
                        items.add(item);
                    }
				}
            }
        }
        return items;
    }

    private void addStaticImports(CompileTask task, CompilationUnitTree root, String partial, boolean endsWithParen, CompletionList list) {
        Trees trees = Trees.instance(task.task);
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        outer:
        for (ImportTree i : root.getImports()) {
            if (!i.isStatic()) continue;
            MemberSelectTree id = (MemberSelectTree) i.getQualifiedIdentifier();
            if (!importMatchesPartial(id.getIdentifier(), partial)) continue;
            TreePath path = trees.getPath(root, id.getExpression());
            TypeElement type = (TypeElement) trees.getElement(path);
            for (Element member : type.getEnclosedElements()) {
                if (!member.getModifiers().contains(Modifier.STATIC)) continue;
                if (!memberMatchesImport(id.getIdentifier(), member)) continue;
                if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
                if (member.getKind() == ElementKind.METHOD) {
                    putMethod((ExecutableElement) member, methods);
                } else {
                    list.items.add(item(member));
                }
                if (list.items.size() + methods.size() > MAX_COMPLETION_ITEMS) {
                    list.isIncomplete = true;
                    break outer;
                }
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.items.addAll(method(overloads, !endsWithParen));
        }
    }

    private boolean isEnclosingClass(DeclaredType type, Scope start) {
        for (Scope s : ScopeHelper.fastScopes(start)) {
            // If we reach a static method, stop looking
            ExecutableElement method = s.getEnclosingMethod();
            if (method != null && method.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
            // If we find the enclosing class
            TypeElement thisElement = s.getEnclosingClass();
            if (thisElement != null && thisElement.asType().equals(type)) {
                return true;
            }
            // If the enclosing class is static, stop looking
            if (thisElement != null && thisElement.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
        }
        return false;
    }

    private boolean importMatchesPartial(Name staticImport, String partial) {
        return staticImport.contentEquals("*") || StringSearch.matchesPartialName(staticImport, partial);
    }

    private boolean memberMatchesImport(Name staticImport, Element member) {
        return staticImport.contentEquals("*") || staticImport.contentEquals(member.getSimpleName());
    }

    private CompletionList completeImport(String path) {
        Set<String> names = new HashSet<>();
        CompletionList list = new CompletionList();
        for (String className : compiler.publicTopLevelTypes()) {
            if (className.startsWith(path)) {
                int start = path.lastIndexOf('.');
                int end = className.indexOf('.', path.length());
                if (end == -1) end = className.length();
                String segment = className.substring(start + 1, end);
                if (names.contains(segment)) continue;
                names.add(segment);
                boolean isClass = end == path.length();
                if (isClass) {
                    list.items.add(classItem(className));
                } else {
                    list.items.add(packageItem(segment));
                }
                if (list.items.size() > MAX_COMPLETION_ITEMS) {
                    list.isIncomplete = true;
                    return list;
                }
            }
        }
        return list;
    }

    private CompletionList completeMemberReference(CompileTask task, TreePath path, String partial) {
        Trees trees = Trees.instance(task.task);
        MemberReferenceTree select = (MemberReferenceTree) path.getLeaf();
        path = new TreePath(path, select.getQualifierExpression());
        Element element = trees.getElement(path);
        boolean isStatic = element instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);
        if (type instanceof ArrayType) {
            return completeArrayMemberReference(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberReference(task, scope, (TypeVariable) type, isStatic, partial);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(task, scope, (DeclaredType) type, isStatic, partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeArrayMemberReference(boolean isStatic) {
        if (isStatic) {
            CompletionList list = new CompletionList();
            list.items.add(keyword("new"));
            return list;
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeTypeVariableMemberReference(
        CompileTask task, Scope scope, TypeVariable type, boolean isStatic, String partial) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(
                task, scope, (DeclaredType) type.getUpperBound(), isStatic, partial);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberReference(
                task, scope, (TypeVariable) type.getUpperBound(), isStatic, partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberReference(CompileTask task, Scope scope, DeclaredType type, boolean isStatic, String partial) {
        Trees trees = Trees.instance(task.task);
        TypeElement typeElement = (TypeElement) type.asElement();
        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : task.task.getElements().getAllMembers(typeElement)) {
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (!isStatic && member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }

            if (list.size() > MAX_COMPLETION_ITEMS) {
                break;
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.addAll(method(overloads, false, true));
        }
        if (isStatic) {
            list.add(keyword("new"));
        }

        CompletionList comp = new CompletionList();
        comp.isIncomplete = !(list.size() > MAX_COMPLETION_ITEMS);
        comp.items = list;
        return comp;
    }

    private CompletionList completeSwitchConstant(CompileTask task, TreePath path, String partial) {
        SwitchTree switchTree = (SwitchTree) path.getLeaf();
        path = new TreePath(path, switchTree.getExpression());
        TypeMirror type = Trees.instance(task.task).getTypeMirror(path);

        if (!(type instanceof DeclaredType)) {
            return new CompletionList();
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        List<CompletionItem> list = new ArrayList<>();
        for (Element member : task.task.getElements().getAllMembers(element)) {
            if (member.getKind() != ElementKind.ENUM_CONSTANT) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            list.add(item(member));
        }

        CompletionList comp = new CompletionList();
        comp.isIncomplete = false;
        comp.items = list;
        return comp;
    }


    private void addClassNames(CompilationUnitTree root, String partial, CompletionList list) {
        String packageName = Objects.toString(root.getPackageName(), "");
        Set<String> uniques = new HashSet<>();
        for (String className : compiler.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
        for (String className : compiler.publicTopLevelTypes()) {
            if (!StringSearch.matchesPartialName(simpleName(className), partial)) continue;
            if (uniques.contains(className)) continue;
            if (list.items.size() > MAX_COMPLETION_ITEMS) {
                list.isIncomplete = true;
                break;
            }
            list.items.add(classItem(className));
            uniques.add(className);
        }
    }

    private void putMethod(ExecutableElement method, Map<String, List<ExecutableElement>> methods) {
        String name = method.getSimpleName().toString();
        if (!methods.containsKey(name)) {
            methods.put(name, new ArrayList<>());
        }
        List<ExecutableElement> elements = methods.get(name);
        if (elements != null) {
            elements.add(method);
        }
    }

    private CompletionItem packageItem(String name) {
        CompletionItem item = new CompletionItem();
        item.label = name;
        item.detail = "";
        item.commitText = name;
        item.cursorOffset = name.length();
        item.iconKind = CircleDrawable.Kind.Package;
        return item;
    }
    private CompletionItem classItem(String className) {
        CompletionItem item = new CompletionItem();
        item.label = simpleName(className).toString();
        item.detail = className.substring(0, className.lastIndexOf("."));
        item.commitText = item.label;
		item.data = className;
        item.cursorOffset = item.label.length();
        item.action = CompletionItem.Kind.IMPORT;
        item.iconKind = CircleDrawable.Kind.Class;
        return item;
    }

	private CompletionItem snippetItem(String label, String snippet) {
		CompletionItem item = new CompletionItem();
		item.label = label;
		item.commitText = snippet;
		item.cursorOffset = item.commitText.length();
		item.detail = "Snippet";
	    item.iconKind = CircleDrawable.Kind.Snippet;
		return item;
	}

    private CompletionItem item(Element element) {
        CompletionItem item = new CompletionItem();
        item.label = element.getSimpleName().toString();
        item.detail = simpleType(element.asType());
        item.commitText = element.getSimpleName().toString();
        item.cursorOffset =  item.commitText.length();
		item.iconKind = getKind(element);


        return item;
    }

    private CompletionItem keyword(String keyword) {
        CompletionItem item = new CompletionItem();
        item.label = keyword;
        item.commitText = keyword;
        item.cursorOffset = keyword.length();
        item.detail = "keyword";
        item.iconKind = CircleDrawable.Kind.Keyword;
        return item;
    }

	private List<CompletionItem> method(List<ExecutableElement> overloads, boolean endsWithParen) {
		return method(overloads, endsWithParen, false);
	}

    private List<CompletionItem> method(List<ExecutableElement> overloads, boolean endsWithParen, boolean methodRef) {
        List<CompletionItem> items = new ArrayList<>();
        for (ExecutableElement first : overloads) {
            CompletionItem item = new CompletionItem();
            item.label = getMethodLabel(first) + getThrowsType(first);
			
            item.commitText = first.getSimpleName().toString() + (methodRef ? "" : "()");
            item.detail = simpleType(first.getReturnType());
            item.iconKind = CircleDrawable.Kind.Method;
            item.cursorOffset = item.commitText.length();
            if (first.getParameters() != null && !first.getParameters().isEmpty()) {
                item.cursorOffset = item.commitText.length() - (methodRef ? 0 : 1);
            }
            items.add(item);
        }
        return items;
    }
	
	private String getThrowsType(ExecutableElement e) {
		if (e.getThrownTypes() == null) {
			return "";
		}
		
		if (e.getThrownTypes().isEmpty()) {
			return "";
		}
		
		StringBuilder types = new StringBuilder();
		for (TypeMirror m : e.getThrownTypes()) {
			types.append((types.length() == 0) ? "" : ", ").append(simpleType(m));
		}
		
		return " throws " + types;
	}
    private String simpleType(TypeMirror mirror) {
        return simpleClassName(mirror.toString());
    }

	private String simpleClassName(String name) {
		return name.replaceAll("[a-zA-Z\\.0-9_\\$]+\\.", "");
	}

    private String getMethodLabel(ExecutableElement element) {
        String name = element.getSimpleName().toString();
        StringBuilder params = new StringBuilder();
        for (VariableElement var : element.getParameters()) {
            params.append((params.length() == 0) ? "" : ", ").append(simpleType(var.asType())).append(" ").append(var.getSimpleName());
        }

        return name + "(" + params + ")";
    }

    private CharSequence simpleName(String className) {
        int dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
    }

	private CircleDrawable.Kind getKind(Element element) {
		switch (element.getKind()) {
            case METHOD:
                return CircleDrawable.Kind.Method; 
            case CLASS:
                return CircleDrawable.Kind.Class;
            case INTERFACE:
                return CircleDrawable.Kind.Interface;
			case FIELD:
				return CircleDrawable.Kind.Filed;
            default:
				return CircleDrawable.Kind.LocalVariable;
        }
	}


    public static boolean hasImport(CompilationUnitTree root, String className) {

        String packageName = className.substring(0, className.lastIndexOf("."));

        // if the package name of the class is java.lang, we dont need 
        // to check since its already imported
        if (packageName.equals("java.lang")) {
            return true;
        }

        for (ImportTree imp : root.getImports()) {
            String name = imp.getQualifiedIdentifier().toString();
            if (name.equals(className)) {
                return true;
            }          

            // if the import is a wildcard, lets check if theyre on the same package
            if (name.endsWith("*")) {
                String first = name.substring(0, name.lastIndexOf("."));
                String end = className.substring(0, className.lastIndexOf("."));
                if (first.equals(end)) {
                    return true;
                }
            }
        }
        return false;
    }
}
