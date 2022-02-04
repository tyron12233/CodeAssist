package com.tyron.completion.java.provider;

import static com.tyron.completion.java.util.CompletionItemFactory.keyword;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.common.util.StringSearch;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.insert.KeywordInsertHandler;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.TreePath;

public class KeywordCompletionProvider extends BaseCompletionProvider {

    private static final String[] TOP_LEVEL_KEYWORDS = {"package", "import", "public", "private",
            "protected", "abstract", "class", "interface", "@interface", "extends", "implements",};

    private static final String[] CLASS_BODY_KEYWORDS = {"public", "private", "protected",
            "static", "final", "native", "synchronized", "abstract", "default", "class",
            "interface", "void", "boolean", "int", "long", "float", "double",};

    private static final String[] METHOD_BODY_KEYWORDS = {"new", "assert", "try", "catch",
            "finally", "throw", "return", "break", "case", "continue", "default", "do", "while",
            "for", "switch", "if", "else", "instanceof", "final", "class", "void", "boolean",
            "int", "long", "float", "double"};

    public KeywordCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    public static void addKeywords(CompileTask task, TreePath path, String partial, CompletionList list) {
        checkCanceled();

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
                CompletionItem keyword = keyword(k);
                keyword.setInsertHandler(new KeywordInsertHandler(task, path, keyword));
                list.items.add(keyword);
            }
        }
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        CompletionList list = new CompletionList();
        addKeywords(task, path, partial, list);
        return list;
    }

    public static Tree findKeywordLevel(TreePath path) {
        while (path != null) {
            checkCanceled();
            if (path.getLeaf() instanceof CompilationUnitTree || path.getLeaf() instanceof ClassTree || path.getLeaf() instanceof MethodTree) {
                return path.getLeaf();
            }
            path = path.getParentPath();
        }
        throw new RuntimeException("empty path");
    }
}
