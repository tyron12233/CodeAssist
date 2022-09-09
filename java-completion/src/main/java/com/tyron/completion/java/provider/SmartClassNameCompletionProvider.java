package com.tyron.completion.java.provider;

import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.insert.ClassImportInsertHandler;
import com.tyron.completion.java.util.CompletionItemFactory;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;

import java.io.File;
import java.util.List;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

public class SmartClassNameCompletionProvider extends BaseCompletionProvider {

    public SmartClassNameCompletionProvider(JavaCompilerService service) {
        super(service);
    }

    @Override
    public void complete(CompletionList.Builder builder,
                         JavacUtilitiesProvider task,
                         TreePath path,
                         String partial,
                         boolean endsWithParen) {
        Types types = task.getTypes();

        // find the parent who declares this variable
        TreePath declaratorPath = TreeUtil.findParentOfType(path, VariableTree.class);
        if (declaratorPath == null) {
            return;
        }

        TypeMirror currentType = task.getTrees().getTypeMirror(declaratorPath);

        List<String> fullyQualifiedNames =
                ClassNameCompletionProvider.getFullyQualifiedNames(path.getCompilationUnit(),
                        partial,
                        task,
                        true);
        for (String fullyQualifiedName : fullyQualifiedNames) {
            TypeElement typeElement = task.getElements().getTypeElement(fullyQualifiedName);
            if (typeElement == null) {
                continue;
            }

            boolean assignable = types.isAssignable(typeElement.asType(), types.erasure(currentType));
            if (assignable) {
                CompletionItem item = CompletionItemFactory.classItem(fullyQualifiedName);
                item.data = fullyQualifiedName;
                item.setInsertHandler(
                        new ClassImportInsertHandler(
                                task,
                                new File(path.getCompilationUnit().getSourceFile().toUri()),
                                item
                        )
                );
                item.setSortText(JavaSortCategory.TO_IMPORT.toString());
                builder.addItem(item);
            }
        }
    }
}
