package com.tyron.completion.java.util;

import androidx.annotation.NonNull;

import com.tyron.completion.InsertionContext;
import com.tyron.completion.TailType;
import com.tyron.completion.lookup.Lookup;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.impl.LookupItem;
import com.tyron.completion.psi.codeInsight.completion.JavaMethodCallElement;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.codeInsight.CodeInsightUtilCore;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiIdentifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiPackageStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiPolyVariantReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.kotlin.com.intellij.psi.ResolveResult;
import org.jetbrains.kotlin.com.intellij.psi.ResolveState;
import org.jetbrains.kotlin.com.intellij.psi.SmartPointerManager;
import org.jetbrains.kotlin.com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.kotlin.com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.light.LightVariableBuilder;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.jetbrains.kotlin.com.intellij.util.ThreeState;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

public class JavaCompletionUtil {
    @NonNull
    public static PsiReferenceExpression createReference(@NonNull String text, @NonNull PsiElement context) {
        return (PsiReferenceExpression) JavaPsiFacade
                .getElementFactory(context.getProject()).createExpressionFromText(text, context);
    }

    public static FakePsiElement createContextWithXxxVariable(@NonNull PsiElement place, @NonNull PsiType varType) {
        return new FakePsiElement() {
            @Override
            public boolean processDeclarations(@NonNull PsiScopeProcessor processor,
                                               @NonNull ResolveState state,
                                               PsiElement lastParent,
                                               @NonNull PsiElement place) {
                return processor.execute(new LightVariableBuilder<>("xxx", varType, place), ResolveState.initial());
            }

            @Override
            public PsiElement getParent() {
                return place;
            }
        };
    }

    public static PsiElement resolveReference(PsiReference psiReference) {
        if (psiReference instanceof PsiPolyVariantReference) {
            ResolveResult[] results = ((PsiPolyVariantReference)psiReference).multiResolve(true);
            if (results.length == 1) return results[0].getElement();
        }
        return psiReference.resolve();
    }

    private static final Key<List<SmartPsiElementPointer<PsiMethod>>> ALL_METHODS_ATTRIBUTE = Key.create("allMethods");

    public static List<PsiMethod> getAllMethods(@NotNull LookupElement item) {
        List<SmartPsiElementPointer<PsiMethod>> pointers = item.getUserData(ALL_METHODS_ATTRIBUTE);
        if (pointers == null) return null;

        return ContainerUtil.mapNotNull(pointers, SmartPsiElementPointer::getElement);
    }

    public static boolean inSomePackage(@NotNull PsiElement context) {
        PsiFile contextFile = context.getContainingFile();
        return contextFile instanceof PsiClassOwner && StringUtil.isNotEmpty(((PsiClassOwner)contextFile).getPackageName());
    }

    public static boolean isSourceLevelAccessible(@NotNull PsiElement context,
                                           @NotNull PsiClass psiClass,
                                           boolean pkgContext) {
        return isSourceLevelAccessible(context, psiClass, pkgContext, psiClass.getContainingClass());
    }

    public static boolean isSourceLevelAccessible(PsiElement context,
                                                  @NotNull PsiClass psiClass,
                                                  boolean pkgContext,
                                                  @Nullable PsiClass qualifierClass) {
        if (!JavaPsiFacade.getInstance(psiClass.getProject()).getResolveHelper().isAccessible(psiClass, context, qualifierClass)) {
            return false;
        }

        if (pkgContext) {
            PsiClass topLevel = PsiUtil.getTopLevelClass(psiClass);
            if (topLevel != null) {
                String fqName = topLevel.getQualifiedName();
                return fqName == null || !StringUtil.isEmpty(StringUtil.getPackageName(fqName));
            }
        }

        return true;
    }

    public static void insertClassReference(@NotNull PsiClass psiClass, @NotNull PsiFile file, int offset) {
        insertClassReference(psiClass, file, offset, offset);
    }

    public static int insertClassReference(PsiClass psiClass, PsiFile file, int startOffset, int endOffset) {
        Project project = file.getProject();
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        documentManager.commitAllDocuments();

        PsiManager manager = file.getManager();

        Document document = FileDocumentManager.getInstance().getDocument(file.getViewProvider().getVirtualFile());

        PsiReference reference = file.findReferenceAt(startOffset);
        if (reference != null && manager.areElementsEquivalent(psiClass, reference.resolve())) {
            return endOffset;
        }

        String name = psiClass.getName();
        if (name == null) {
            return endOffset;
        }

        if (reference != null && !psiClass.hasModifierProperty(PsiModifier.STATIC)) {
            PsiClass containingClass = psiClass.getContainingClass();
            if (containingClass != null && containingClass.hasTypeParameters()) {
                PsiModifierListOwner enclosingStaticElement = PsiUtil.getEnclosingStaticElement(reference.getElement(), null);
                if (enclosingStaticElement != null && !PsiTreeUtil.isAncestor(enclosingStaticElement, psiClass, false)) {
                    return endOffset;
                }
            }
        }

        assert document != null;
        document.replaceString(startOffset, endOffset, name);

        int newEndOffset = startOffset + name.length();
        RangeMarker toDelete = insertTemporary(newEndOffset, document, " ");

        documentManager.commitAllDocuments();

        PsiElement element = file.findElementAt(startOffset);
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            if (parent instanceof PsiJavaCodeReferenceElement) {
                PsiJavaCodeReferenceElement ref = (PsiJavaCodeReferenceElement) parent;
                if (!ref.isQualified() &&
                    !(parent.getParent() instanceof PsiPackageStatement) &&
                    psiClass.isValid() &&
                    !psiClass.getManager().areElementsEquivalent(psiClass, resolveReference(ref))) {
                    boolean staticImport = ref instanceof PsiImportStaticReferenceElement;
                    PsiElement newElement;
                    try {
                        newElement =
                                staticImport ?
                                        ((PsiImportStaticReferenceElement) ref).bindToTargetClass(
                                        psiClass) : ref.bindToElement(psiClass);
                    } catch (IncorrectOperationException e) {
                        return endOffset; // can happen if fqn contains reserved words, for example
                    }
                    SmartPsiElementPointer<PsiClass> classPointer =
                            SmartPointerManager.createPointer(psiClass);

                    RangeMarker rangeMarker = document.createRangeMarker(newElement.getTextRange());
                    documentManager.doPostponedOperationsAndUnblockDocument(document);
                    documentManager.commitDocument(document);

                    newElement = findElementInRange(file,
                            rangeMarker.getStartOffset(),
                            rangeMarker.getEndOffset(),
                            PsiJavaCodeReferenceElement.class,
                            JavaLanguage.INSTANCE);
                    rangeMarker.dispose();
                    if (newElement != null) {
                        newEndOffset = newElement.getTextRange().getEndOffset();
                        if (!(newElement instanceof PsiReferenceExpression)) {
                            PsiReferenceParameterList parameterList =
                                    ((PsiJavaCodeReferenceElement) newElement).getParameterList();
                            if (parameterList != null) {
                                newEndOffset = parameterList.getTextRange().getStartOffset();
                            }
                        }
                        psiClass = classPointer.getElement();

                        if (!staticImport &&
                            psiClass != null &&
                            !psiClass.getManager()
                                    .areElementsEquivalent(psiClass,
                                            resolveReference((PsiReference) newElement)) &&
                            !PsiUtil.isInnerClass(psiClass)) {
                            String qName = psiClass.getQualifiedName();
                            if (qName != null) {
                                document.replaceString(newElement.getTextRange().getStartOffset(),
                                        newEndOffset,
                                        qName);
                                newEndOffset =
                                        newElement.getTextRange().getStartOffset() + qName.length();
                            }
                        }
                    }
                }
            }
        }

        if (toDelete != null && toDelete.isValid()) {
            document.deleteString(toDelete.getStartOffset(), toDelete.getEndOffset());
        }

        return newEndOffset;
    }

    @Nullable
    public static RangeMarker insertTemporary(int endOffset, Document document, String temporary)  {
        CharSequence chars = document.getCharsSequence();
        if (endOffset < chars.length() && Character.isJavaIdentifierPart(chars.charAt(endOffset))){
            document.insertString(endOffset, temporary);
            RangeMarker toDelete = document.createRangeMarker(endOffset, endOffset + 1);
            toDelete.setGreedyToLeft(true);
            toDelete.setGreedyToRight(true);
            return toDelete;
        }
        return null;
    }



    public static <T extends PsiElement> T findElementInRange(@NotNull PsiFile file,
                                                              int startOffset,
                                                              int endOffset,
                                                              @NotNull Class<T> klass,
                                                              @NotNull Language language) {
        return findElementInRange(file, startOffset, endOffset, klass, language, null);
    }

    @Nullable
    private static <T extends PsiElement> T findElementInRange(@NotNull PsiFile file,
                                                               int startOffset,
                                                               int endOffset,
                                                               @NotNull Class<T> klass,
                                                               @NotNull Language language,
                                                               @Nullable PsiElement initialElement) {
        PsiElement element1 = file.getViewProvider().findElementAt(startOffset, language);
        PsiElement element2 = file.getViewProvider().findElementAt(endOffset - 1, language);
        if (element1 instanceof PsiWhiteSpace) {
            startOffset = element1.getTextRange().getEndOffset();
            element1 = file.getViewProvider().findElementAt(startOffset, language);
        }
        if (element2 instanceof PsiWhiteSpace) {
            endOffset = element2.getTextRange().getStartOffset();
            element2 = file.getViewProvider().findElementAt(endOffset - 1, language);
        }
        if (element2 == null || element1 == null) return null;
        final PsiElement commonParent = PsiTreeUtil.findCommonParent(element1, element2);
        final T element =
                ReflectionUtil.isAssignable(klass, commonParent.getClass())
                        ? (T)commonParent : PsiTreeUtil.getParentOfType(commonParent, klass);

        if (element == initialElement) {
            return element;
        }

        if (element == null || element.getTextRange().getStartOffset() != startOffset || element.getTextRange().getEndOffset() != endOffset) {
            return null;
        }
        return element;
    }

    public static void insertParentheses(@NotNull InsertionContext context,
                                         @NotNull LookupElement item,
                                         boolean overloadsMatter,
                                         boolean hasParams) {
        insertParentheses(context, item, overloadsMatter, ThreeState.fromBoolean(hasParams), false);
    }

    public static void insertParentheses(@NotNull InsertionContext context,
                                         @NotNull LookupElement item,
                                         boolean overloadsMatter,
                                         ThreeState hasParams,
                                         // UNSURE if providing no arguments is a valid situation
                                         boolean forceClosingParenthesis) {
        Editor editor = context.getEditor();
        char completionChar = context.getCompletionChar();
        PsiFile file = context.getFile();
//
//        TailType tailType = completionChar == '(' ? TailType.NONE :
//                completionChar == ':' ? TailType.COND_EXPR_COLON :
//                        LookupItem.handleCompletionChar(context.getEditor(), item, completionChar);
//        boolean hasTail = tailType != TailType.NONE && tailType != TailType.UNKNOWN;
//        boolean smart = completionChar == Lookup.COMPLETE_STATEMENT_SELECT_CHAR;
//
//        if (completionChar == '(' || completionChar == '.' || completionChar == ',' || completionChar == ';' || completionChar == ':' || completionChar == ' ') {
//            context.setAddCompletionChar(false);
//        }
//
//        if (hasTail) {
//            hasParams = ThreeState.NO;
//        }
//        boolean needRightParenth = forceClosingParenthesis ||
//                                   !smart && (CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET ||
//                                              hasParams == ThreeState.NO && completionChar != '(');
//
//        context.commitDocument();
//
//        CommonCodeStyleSettings styleSettings = CompletionStyleUtil.getCodeStyleSettings(context);
//        PsiElement elementAt = file.findElementAt(context.getStartOffset());
//        if (elementAt == null || !(elementAt.getParent() instanceof PsiMethodReferenceExpression)) {
//            ThreeState hasParameters = hasParams;
//            boolean spaceBetweenParentheses = hasParams == ThreeState.YES && styleSettings.SPACE_WITHIN_METHOD_CALL_PARENTHESES ||
//                                              hasParams == ThreeState.UNSURE && styleSettings.SPACE_WITHIN_EMPTY_METHOD_CALL_PARENTHESES;
//            new ParenthesesInsertHandler<>(styleSettings.SPACE_BEFORE_METHOD_CALL_PARENTHESES, spaceBetweenParentheses,
//                    needRightParenth, styleSettings.METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE) {
//                @Override
//                protected boolean placeCaretInsideParentheses(InsertionContext context1, LookupElement item1) {
//                    return hasParameters != ThreeState.NO;
//                }
//
//                @Override
//                protected PsiElement findExistingLeftParenthesis(@NotNull InsertionContext context) {
//                    PsiElement token = super.findExistingLeftParenthesis(context);
//                    return isPartOfLambda(token) ? null : token;
//                }
//
//                private boolean isPartOfLambda(PsiElement token) {
//                    return token != null && token.getParent() instanceof PsiExpressionList &&
//                           PsiUtilCore.getElementType(PsiTreeUtil.nextVisibleLeaf(token.getParent())) == JavaTokenType.ARROW;
//                }
//            }.handleInsert(context, item);
//        }
//
//        if (hasParams != ThreeState.NO) {
//            // Invoke parameters popup
//            AutoPopupController.getInstance(file.getProject()).autoPopupParameterInfo(editor, overloadsMatter ? null : (PsiElement)item.getObject());
//        }
//
//        if (smart || !needRightParenth || !EditorSettingsExternalizable.getInstance().isInsertParenthesesAutomatically() ||
//            !insertTail(context, item, tailType, hasTail)) {
//            return;
//        }
//
//        if (completionChar == '.') {
//            AutoPopupController.getInstance(file.getProject()).autoPopupMemberLookup(context.getEditor(), null);
//        } else if (completionChar == ',') {
//            AutoPopupController.getInstance(file.getProject()).autoPopupParameterInfo(context.getEditor(), null);
//        }
    }

    public static boolean isInExcludedPackage(PsiClass aClass, boolean b) {
        return false;
    }
}
