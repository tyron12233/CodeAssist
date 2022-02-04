package com.tyron.completion.java.provider;

import static com.tyron.completion.java.provider.ClassNameCompletionProvider.addClassNames;
import static com.tyron.completion.java.provider.ImportCompletionProvider.addStaticImports;
import static com.tyron.completion.java.provider.SwitchConstantCompletionProvider.completeSwitchConstant;
import static com.tyron.completion.progress.ProgressManager.checkCanceled;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import org.openjdk.source.tree.CaseTree;
import org.openjdk.source.util.TreePath;

public class IdentifierCompletionProvider extends BaseCompletionProvider {

    public IdentifierCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public CompletionList complete(CompileTask task, TreePath path, String partial,
                                   boolean endsWithParen) {
        checkCanceled();

        if (path.getParentPath().getLeaf() instanceof CaseTree) {
            return completeSwitchConstant(task, path.getParentPath(), partial);
        }

        CompletionList list = new CompletionList();
        ScopeCompletionProvider.addCompletionItems(task, path, partial, endsWithParen, list);
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, list);
        if (!list.isIncomplete && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), partial, list, getCompiler());
        }

        KeywordCompletionProvider.addKeywords(task, path, partial, list);

        return list;
    }
}
