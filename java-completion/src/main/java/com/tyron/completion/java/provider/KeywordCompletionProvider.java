package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.keyword;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.insert.KeywordInsertHandler;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class KeywordCompletionProvider extends BaseCompletionProvider {

    private static final String[] TOP_LEVEL_KEYWORDS = {"package", "import", "public", "private",
            "protected", "abstract", "class", "interface", "@interface", "extends", "implements",};

    private static final String[] CLASS_BODY_KEYWORDS = {"public", "private", "protected",
            "static", "final", "native", "synchronized", "abstract", "default", "class",
            "interface", "void", "boolean", "int", "long", "float", "double", "implements", "extends"};

    private static final String[] METHOD_BODY_KEYWORDS = {"new", "assert", "try", "catch",
            "finally", "throw", "return", "break", "case", "continue", "default", "do", "while",
            "for", "switch", "if", "else", "instanceof", "final", "class", "void", "boolean",
            "int", "long", "float", "double"};

    private static final String[] CLASS_LEVEL_KEYWORDS = {"public", "private", "protected",
            "abstract", "class", "interface", "@interface", "extends", "implements"};

    public KeywordCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    public static void addKeywords(CompileTask task, TreePath path, String partial, CompletionList.Builder list) {
        checkCanceled();

        if (!partial.isEmpty() && Character.isUpperCase(partial.charAt(0))) {
            // keywords are only lowercase
            return;
        }

        TreePath keywordPath = findKeywordLevel(path);
        Tree level = keywordPath.getLeaf();

        Set<String> keywords = new HashSet<>();
        if (level instanceof CompilationUnitTree) {
            keywords.addAll(Arrays.asList(TOP_LEVEL_KEYWORDS));
        } else if (level instanceof ClassTree) {
            keywords.addAll(Arrays.asList(CLASS_BODY_KEYWORDS));
        } else if (level instanceof MethodTree) {
            keywords.addAll(Arrays.asList(METHOD_BODY_KEYWORDS));
        }

        for (String k : keywords) {
            if (StringSearch.matchesPartialName(k, partial)) {
                CompletionItem keyword = keyword(k);
                keyword.setInsertHandler(new KeywordInsertHandler(task, path, keyword));
                keyword.setSortText(JavaSortCategory.KEYWORD.toString());
                list.addItem(keyword);
            }
        }
    }

    @Override
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        addKeywords(task, path, partial, builder);
    }

    public static TreePath findKeywordLevel(TreePath path) {
        while (path != null) {
            checkCanceled();
            if (path.getLeaf() instanceof CompilationUnitTree || path.getLeaf() instanceof ClassTree || path.getLeaf() instanceof MethodTree) {
                return path;
            }
            path = path.getParentPath();
        }
        throw new RuntimeException("empty path");
    }
}
