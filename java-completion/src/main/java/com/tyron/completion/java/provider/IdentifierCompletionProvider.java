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
    public void complete(CompletionList.Builder builder, CompileTask task, TreePath path, String partial, boolean endsWithParen) {
        checkCanceled();

        if (path.getParentPath().getLeaf() instanceof CaseTree) {
            completeSwitchConstant(builder, task, path.getParentPath(), partial);
            return;
        }

        ScopeCompletionProvider.addCompletionItems(task, path, partial, endsWithParen, builder);
        addStaticImports(task, path.getCompilationUnit(), partial, endsWithParen, builder);
        if (!builder.isIncomplete() && partial.length() > 0 && Character.isUpperCase(partial.charAt(0))) {
            addClassNames(path.getCompilationUnit(), partial, builder, getCompiler());
        }

        KeywordCompletionProvider.addKeywords(task, path, partial, builder);
    }
}
