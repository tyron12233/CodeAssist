package com.tyron.kotlin_completion.action;

import static org.jetbrains.kotlin.js.resolve.diagnostics.SourceLocationUtilsKt.findPsi;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.completion.model.Rewrite;
import com.tyron.completion.model.TextEdit;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;
import com.tyron.kotlin_completion.CompiledFile;
import com.tyron.kotlin_completion.util.PsiUtils;

import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtElement;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtModifierList;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry;
import org.jetbrains.kotlin.psi.KtTypeReference;
import javax.tools.Diagnostic;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import kotlin.text.StringsKt;

public class ImplementAbstractFunctionsQuickFix extends QuickFix {

    public static final String ID = "kotlinImplementAbstractFunctionsQuickFIx";
    public static final String ERROR_CODE = "ABSTRACT_MEMBER_NOT_IMPLEMENTED";
    public static final String ERROR_CODE_CLASS = "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED";
    @Override
    public boolean accept(@NonNull String errorCode) {
        return ERROR_CODE.equals(errorCode)
                || ERROR_CODE_CLASS.equals(errorCode);
    }

    @Override
    public String getTitle(Context context) {
        return "Implement Abstract Functions";
    }



    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        File file = e.getRequiredData(CommonDataKeys.FILE);
        Caret caret = editor.getCaret();
        Diagnostic<?> diagnostic = e.getRequiredData(CommonDataKeys.DIAGNOSTIC);
        CompiledFile compiledFile = e.getRequiredData(CommonKotlinKeys.COMPILED_FILE);

        KtElement kotlinClass = compiledFile.parseAtPoint(caret.getStart(), false);

        if (kotlinClass instanceof KtClass) {
            List<String> functions = getAbstractFunctionsStubs(compiledFile,
                    (KtClass) kotlinClass);
            Rewrite<Void> rewrite = new Rewrite<Void>() {
                @Override
                public Map<Path, TextEdit[]> rewrite(Void unused) {
                    return Collections.emptyMap();
                }
            };

            RewriteUtil.performRewrite(editor, file, null, rewrite);
        }
    }

    private List<String> getAbstractFunctionsStubs(CompiledFile file, KtClass kotlinClass) {
        List<KtSuperTypeListEntry> superTypeListEntries = kotlinClass.getSuperTypeListEntries();
        Stream<List<String>> streamStream = superTypeListEntries.stream().map(it -> {
            Pair<KtExpression, DeclarationDescriptor> pair =
                    file.referenceAtPoint(PsiUtils.getStartOffset(it));
            if (pair == null) {
                return null;
            }
            DeclarationDescriptor descriptor = pair.getSecond();
            if (descriptor == null) {
                return null;
            }
            PsiElement superClass = findPsi(descriptor);
            if (!(superClass instanceof KtClass)) {
                return null;
            }
            KtClass ktSuperClass = (KtClass) superClass;
            if (isAbstractOrInterface(ktSuperClass)) {
                return ktSuperClass.getDeclarations().stream()
                        .filter(decl -> isAbstractFunction(decl) && !overridesDeclaration(kotlinClass, decl))
                        .map(function -> getFunctionStub(((KtNamedFunction) function)))
                        .collect(Collectors.toList());
            }
            return null;
        }).filter(Objects::nonNull);

        List<String> strings = new ArrayList<>();
        streamStream.forEach(strings::addAll);
        return strings;
    }

    private String getFunctionStub(KtNamedFunction function) {
        return "override fun" + StringsKt.substringAfter(function.getText(), "fun", "") + " { }";
    }

    private boolean isAbstractOrInterface(KtClass ktClass) {
        if (ktClass.isInterface()) {
            return true;
        }
        KtModifierList modifierList = ktClass.getModifierList();
        if (modifierList != null) {
            return modifierList.hasModifier(KtTokens.ABSTRACT_KEYWORD);
        }
        return false;
    }

    private boolean isAbstractFunction(KtDeclaration decl) {
        if (decl instanceof KtNamedFunction) {
            if (((KtNamedFunction) decl).hasBody()) {
                return false;
            }

            KtModifierList modifierList = decl.getModifierList();
            if (modifierList == null) {
                return false;
            }
            return modifierList.hasModifier(KtTokens.ABSTRACT_KEYWORD);
        }
        return false;
    }

    private boolean overridesDeclaration(KtClass ktClass, KtDeclaration declaration) {
        List<KtDeclaration> declarations = ktClass.getDeclarations();
        return declarations.stream().anyMatch(it -> {
            String name = it.getName();
            if (name == null) {
                return false;
            }
            if (name.equals(declaration.getName()) && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
                if (it instanceof KtNamedFunction && declaration instanceof KtNamedFunction) {
                    return parametersMatch(((KtNamedFunction) it), ((KtNamedFunction) declaration));
                } else {
                    return true;
                }
            } else {
                return false;
            }
        });
    }

    private boolean parametersMatch(KtNamedFunction function, KtNamedFunction functionDeclaration) {
        if (function.getValueParameters().size() == functionDeclaration.getValueParameters().size()) {
            for (int i = 0; i < function.getValueParameters().size(); i++) {
                String fName = function.getValueParameters().get(i).getName();
                String fdName = functionDeclaration.getName();
                if (!Objects.equals(fName, fdName)) {
                    return false;
                }

                KtTypeReference typeReference =
                        function.getValueParameters().get(i).getTypeReference();
                KtTypeReference fdTypeReference = functionDeclaration.getTypeReference();
                if (!Objects.equals(typeReference, fdTypeReference)) {
                    return false;
                }
            }

            if (function.getTypeParameters().size() == functionDeclaration.getTypeParameters().size()) {
                for (int i = 0; i < function.getTypeParameters().size(); i++) {
                    if (!function.getTypeParameters().get(i).getVariance().equals(functionDeclaration.getTypeParameters().get(i).getVariance())) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }
}
