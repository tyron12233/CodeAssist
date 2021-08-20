package com.tyron.code.completion;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;
import com.tyron.code.ApplicationLoader;
import com.sun.source.util.JavacTask;
import com.tyron.code.model.CompletionList;
import com.tyron.code.util.StringSearch;
import com.tyron.code.model.CompletionItem;
import com.sun.source.tree.ImportTree;
import javax.lang.model.type.TypeMirror;
import com.tyron.code.parser.JavaParser;
import java.util.Set;
import android.util.Log;
import com.tyron.code.model.TextEdit;
import com.tyron.code.rewrite.AddImport;
import com.tyron.code.ParseTask;
import java.io.File;
import java.util.Collections;
import javax.lang.model.element.TypeParameterElement;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ExpressionTree;

/**
 * Main entry point for getting completions
 */
public class CompletionProvider {
    
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
    
    private final JavaParser parser;

    private static final int MAX_COMPLETION_ITEMS = 50;
    
    public CompletionProvider(JavaParser parser) {
        this.parser = parser;
    }
    
    public CompletionList complete(CompilationUnitTree root, int index) {
        long started = System.currentTimeMillis();
        long cursor = index;
        StringBuilder contents = new PruneMethodBodies(parser.getTask()).scan(root, cursor);
        int end = endOfLine(contents, (int) cursor);
       // contents.insert(end, ';');
        
        String partial = partialIdentifier(contents.toString(), (int) cursor);
        //ApplicationLoader.showToast(partial);
        boolean endsWithParen = endsWithParen(contents.toString(), (int) cursor);
        TreePath path = new FindCompletionsAt(parser.getTask()).scan(root, cursor);
        switch (path.getLeaf().getKind()) {
            case IDENTIFIER:             
                return completeIdentifier(path, partial, endsWithParen);
            case MEMBER_SELECT:              
                return completeMemberSelect(path, partial, endsWithParen);
            case MEMBER_REFERENCE:
                return completeMemberReference(path, partial);
            case CASE:
                return completeSwitchConstant(path, partial);
            case IMPORT:
                return completeImport(qualifiedPartialIdentifier(contents.toString(), (int) cursor));
            default:
                CompletionList list = new CompletionList();
                addKeywords(path, partial, list);
                return list;
        }
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

    private boolean isQualifiedIdentifierChar(char c) {
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
    
    private CompletionList completeIdentifier(TreePath path, final String partial, boolean endsWithParen) {
        CompletionList list = new CompletionList();
        list.items = completeUsingScope(path, partial, endsWithParen);
        addStaticImports(path.getCompilationUnit(), partial, endsWithParen, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), partial, list);
        }
        addKeywords(path, partial, list);
        return list;
    }
    
    private CompletionList completeMemberSelect(TreePath path, String partial, boolean endsWithParen) {
        Trees trees = Trees.instance(parser.getTask());
        MemberSelectTree select = (MemberSelectTree) path.getLeaf();     
        path = new TreePath(path, select.getExpression());
        boolean isStatic = trees.getElement(path) instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);
        Log.d("Completion on MemberSelect", "type: " + type.getKind() + " " + type.getClass().getName());
        if (type instanceof ArrayType) {
            return completeArrayMemberSelect(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(scope, (TypeVariable) type, isStatic, partial, endsWithParen);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect(scope, (DeclaredType) type, isStatic, partial, endsWithParen);
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

    private CompletionList completeTypeVariableMemberSelect(Scope scope, TypeVariable type, boolean isStatic, String partial, boolean endsWithParen) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberSelect( scope, (DeclaredType) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberSelect(scope, (TypeVariable) type.getUpperBound(), isStatic, partial, endsWithParen);
        } else {
            return new CompletionList();
        }
    }
    
    private CompletionList completeDeclaredTypeMemberSelect(Scope scope, DeclaredType type, boolean isStatic, String partial, boolean endsWithParen) {
        Trees trees = Trees.instance(parser.getTask());
        TypeElement typeElement = (TypeElement) type.asElement();
        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<String, List<ExecutableElement>>();
        for (Element member : parser.getTask().getElements().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) continue;
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            Log.d("DeclaredTypeCompletion", "member name: " + member.getSimpleName());
          //  if (isStatic != member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.add(method(overloads, !endsWithParen));
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
    
   
    private List<CompletionItem> completeUsingScope(TreePath path, final String partial, boolean endsWithParen) {
         Trees trees = Trees.instance(parser.getTask());
         List<CompletionItem> list = new ArrayList<>();
         HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
         Scope scope = trees.getScope(path);
         
         Log.d("IDENTIFIER PATH", path.getParentPath().getLeaf().getKind().toString());
         
         Predicate<CharSequence> filter = new Predicate() {
            @Override
            public boolean test(Object p1) {
                return StringSearch.matchesPartialName(String.valueOf(p1), partial);
            }         
         };
         
         if (path.getParentPath().getLeaf().getKind() == Tree.Kind.METHOD_INVOCATION) {
             //list.addAll(addLambdas(path.getParentPath(), partial));
             list.addAll(completeMethodInvocation(path.getParentPath(), partial));
         }
         for (Element element : ScopeHelper.scopeMembers(parser.getTask(), scope, filter)) {
              if (element.getKind() == ElementKind.METHOD) {
                  putMethod((ExecutableElement) element, methods);
              } else {
                  list.add(item(element));
              }
         }
         
         for (List<ExecutableElement> overloads : methods.values()) {
             list.add(method(overloads, !endsWithParen));
         }
         return list;
    }
    
    public List<CompletionItem> completeMethodInvocation(TreePath path, String partial) {
        Trees trees = Trees.instance(parser.getTask());
        List<CompletionItem> items = new ArrayList<>();
        MethodInvocationTree method = (MethodInvocationTree) path.getLeaf();
        Element element = trees.getElement(path);
        ExecutableElement executable = (ExecutableElement) element;
       
        int argumentToComplete= 0;
        for (int i = 0; i < method.getArguments().size(); i++) {
            ExpressionTree exp = method.getArguments().get(i);
            if (exp.toString().equals(partial)) {
                argumentToComplete = i;
            }
        }
        
        VariableElement var = executable.getParameters().get(argumentToComplete);
        if (var.asType() instanceof DeclaredType) {
            DeclaredType type = (DeclaredType) var.asType();
            Element classElement = type.asElement();
            
            Log.d("FINAL STEP", "Declared type is declared type");
            if (classElement.getKind() == ElementKind.CLASS || classElement.getKind() == ElementKind.INTERFACE) {
                
                Log.d("FINAL STEP", "Element is class or interface");
                
                Map<String, List<ExecutableElement>> methods = new HashMap<>();
                
                Log.d("FINAL STEP", "enclosed methods: " + classElement.getEnclosedElements().size());
                for (Element enc : classElement.getEnclosedElements()) {
                    if (enc.getKind() == ElementKind.METHOD) {
                        if (enc.getModifiers().contains(Modifier.STATIC)) {
                            continue;
                        }
                        putMethod((ExecutableElement) enc, methods);
                    }
                }
                
                if (methods.values().size() == 1) {
                    ExecutableElement sam = methods.values().iterator().next().iterator().next();
                    
                    CompletionItem item = new CompletionItem();
                    
                    String label = "";
                    for (VariableElement param : sam.getParameters()) {
                        label = label + (label.isEmpty() ? "" : ", ") + param.getSimpleName().toString();
                    }
                    
                    item.label = "(" + label + ")" + " -> ";
                    item.commitText = item.label;
                    item.detail = simpleName(sam.getReturnType().toString()).toString();
                    item.cursorOffset = item.label.length();
                    items.add(item);
                }
            }
        }
        
        
        Log.d("TYPE TYPE", executable.getParameters().iterator().next().asType().toString());
        Log.d("EXEC EXEC", "type: " + executable.getTypeParameters() + "args: " + executable.getParameters() + "ec: " + executable.getEnclosedElements());
       // for (int i = 0; i < executable.getT
        Log.d("EXECUTABLE ELEMENT", executable.toString() + "Type: " + method.getTypeArguments() + " args:" + method.getArguments());
        return items;
    }
    
    public List<CompletionItem> addLambdas(TreePath path, String partial) {
        List<CompletionItem> items = new ArrayList<>();
        Trees trees = Trees.instance(parser.getTask());
        MethodInvocationTree method = (MethodInvocationTree) path.getLeaf();
        path = new TreePath(path, method.getMethodSelect());
        return items;
    }
    
    private void addStaticImports(CompilationUnitTree root, String partial, boolean endsWithParen, CompletionList list) {
        Trees trees = Trees.instance(parser.getTask());
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        int previousSize = list.items.size();
        outer:
        for ( ImportTree i : root.getImports()) {
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
            list.items.add(method(overloads, !endsWithParen));
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
        for (String className : parser.publicTopLevelTypes()) {
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
    
    private CompletionList completeMemberReference(TreePath path, String partial) {
        Trees trees = Trees.instance(parser.getTask());
        MemberReferenceTree select = (MemberReferenceTree) path.getLeaf();
        
        path = new TreePath(path, select.getQualifierExpression());
        Element element = trees.getElement(path);
        boolean isStatic = element instanceof TypeElement;
        Scope scope = trees.getScope(path);
        TypeMirror type = trees.getTypeMirror(path);
        if (type instanceof ArrayType) {
            return completeArrayMemberReference(isStatic);
        } else if (type instanceof TypeVariable) {
            return completeTypeVariableMemberReference(scope, (TypeVariable) type, isStatic, partial);
        } else if (type instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(scope, (DeclaredType) type, isStatic, partial);
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
        Scope scope, TypeVariable type, boolean isStatic, String partial) {
        if (type.getUpperBound() instanceof DeclaredType) {
            return completeDeclaredTypeMemberReference(
                scope, (DeclaredType) type.getUpperBound(), isStatic, partial);
        } else if (type.getUpperBound() instanceof TypeVariable) {
            return completeTypeVariableMemberReference(
                scope, (TypeVariable) type.getUpperBound(), isStatic, partial);
        } else {
            return new CompletionList();
        }
    }

    private CompletionList completeDeclaredTypeMemberReference(Scope scope, DeclaredType type, boolean isStatic, String partial) {
        Trees trees = Trees.instance(parser.getTask());
        TypeElement typeElement = (TypeElement) type.asElement();
        List<CompletionItem> list = new ArrayList<>();
        HashMap<String, List<ExecutableElement>> methods = new HashMap<>();
        for (Element member : parser.getTask().getElements().getAllMembers(typeElement)) {
            if (!StringSearch.matchesPartialName(member.getSimpleName(), partial)) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!trees.isAccessible(scope, member, type)) continue;
            if (!isStatic && member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getKind() == ElementKind.METHOD) {
                putMethod((ExecutableElement) member, methods);
            } else {
                list.add(item(member));
            }
        }
        for (List<ExecutableElement> overloads : methods.values()) {
            list.add(method( overloads, false));
        }
        if (isStatic) {
            list.add(keyword("new"));
        }
        
        CompletionList comp = new CompletionList();
        comp.isIncomplete = false;
        comp.items = list;
        return comp;
    }
    
    private CompletionList completeSwitchConstant(TreePath path, String partial) {
        SwitchTree switchTree = (SwitchTree) path.getLeaf();
        path = new TreePath(path, switchTree.getExpression());
        TypeMirror type = Trees.instance(parser.getTask()).getTypeMirror(path);
        
        if (!(type instanceof DeclaredType)) {
            return new CompletionList();
        }
        DeclaredType declared = (DeclaredType) type;
        TypeElement element = (TypeElement) declared.asElement();
        List<CompletionItem> list = new ArrayList<>();
        for (Element member : parser.getTask().getElements().getAllMembers(element)) {
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
        Set<String> uniques = new HashSet<String>();
        int previousSize = list.items.size();
        for (String className : parser.packagePrivateTopLevelTypes(packageName)) {
            if (!StringSearch.matchesPartialName(className, partial)) continue;
            list.items.add(classItem(className));
            uniques.add(className);
        }
        for (String className : parser.publicTopLevelTypes()) {
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
        methods.get(name).add(method);
    }
    
    private CompletionItem packageItem(String name) {
        CompletionItem item = new CompletionItem();
        item.label = name;
        item.detail = "";
        item.commitText = name;
        item.cursorOffset = name.length();
        return item;
    }
    private CompletionItem classItem(String className) {
        CompletionItem item = new CompletionItem();
        item.label = simpleName(className).toString();
        item.detail = className;
        item.commitText = item.label;
        item.cursorOffset = item.label.length();
        item.kind = CompletionItem.Kind.IMPORT;
        return item;
    }
    
    private CompletionItem item(Element element) {
        CompletionItem item = new CompletionItem();
        item.label = element.getSimpleName().toString();
        item.detail = element.asType().toString();
        item.commitText = element.getSimpleName().toString();
        item.cursorOffset =  item.commitText.length();
        return item;
    }
    
    private CompletionItem keyword(String keyword) {
        CompletionItem item = new CompletionItem();
        item.label = keyword;
        item.commitText = keyword;
        item.cursorOffset = keyword.length();
        item.detail = "keyword";
        return item;
    }
    
    private CompletionItem method(List<ExecutableElement> overloads, boolean endsWithParen) {
        ExecutableElement first = overloads.get(0);
        CompletionItem item = new CompletionItem();
        item.label = getMethodLabel(first);
        item.commitText = first.getSimpleName().toString() + "()";
        item.detail = first.getReturnType().toString();
        item.cursorOffset = item.commitText.length();
        if (first.getParameters() != null && !first.getParameters().isEmpty()) {
            item.cursorOffset = item.commitText.length() - 1;
        }
        return item;
    }
    
    private String getMethodLabel(ExecutableElement element) {
        String string = element.toString();
        String identifier = element.getSimpleName().toString();
        
        String paramsString = string.substring(string.indexOf("(") + 1, string.lastIndexOf(")"));
        
        
        String params = "";
        if (paramsString.contains(",")) {
            String[] split = paramsString.split(",");
            for (String s : split) {
                params = params + (params.isEmpty() ? "" : ", ") + simpleName(s);
            }
        } else {
            params = simpleName(paramsString).toString();
        }
        return identifier + "(" + params + ")";
    }
    
    private CharSequence simpleName(String className) {
        int dot = className.lastIndexOf('.');
        if (dot == -1) return className;
        return className.subSequence(dot + 1, className.length());
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
                String end = className.substring(0, name.lastIndexOf("."));
                if (first.equals(end)) {
                    return true;
                }
            }
        }
        return false;
    }
}
