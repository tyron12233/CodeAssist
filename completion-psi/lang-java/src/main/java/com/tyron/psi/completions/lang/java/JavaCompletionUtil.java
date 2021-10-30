package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionUtil;
import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.completions.lang.java.lookup.TypedLookupItem;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.util.DocumentUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.codeInsight.CodeInsightUtilCore;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.RangeMarkerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiImmediateClassType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class JavaCompletionUtil {

    private static final Logger LOG = Logger.getInstance(JavaCompletionUtil.class);

    @NotNull
    public static String escapeXmlIfNeeded(InsertionContext context, @NotNull String generics) {
        if (context.getFile().getViewProvider().getBaseLanguage().getClass().getName().contains("JspxLanguage")) {
            return StringUtil.escapeXmlEntities(generics);
        }
        return generics;
    }

    //need to shorten references in type argument list
    public static void shortenReference(final PsiFile file, final int offset) throws IncorrectOperationException {
        Project project = file.getProject();
        final PsiDocumentManager manager = PsiDocumentManager.getInstance(project);
        Document document = manager.getDocument(file);
        if (document == null) {
            PsiUtilCore.ensureValid(file);
            LOG.error("No document for " + file);
            return;
        }

        manager.commitDocument(document);
        PsiReference ref = file.findReferenceAt(offset);
        if (ref != null) {
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(ref.getElement());
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document);
        }
    }

    public static void insertClassReference(@NotNull PsiClass psiClass, @NotNull PsiFile file, int offset) {
        insertClassReference(psiClass, file, offset, offset);
    }

    public static int insertClassReference(PsiClass psiClass, PsiFile file, int startOffset, int endOffset) {
        final Project project = file.getProject();
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        documentManager.commitAllDocuments();

        final PsiManager manager = file.getManager();

        final Document document = FileDocumentManager.getInstance().getDocument(file.getViewProvider().getVirtualFile());

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
        final RangeMarker toDelete = insertTemporary(newEndOffset, document, " ");

        documentManager.commitAllDocuments();

        PsiElement element = file.findElementAt(startOffset);
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            if (parent instanceof PsiJavaCodeReferenceElement &&
                    !((PsiJavaCodeReferenceElement)parent).isQualified() &&
                    !(parent.getParent() instanceof PsiPackageStatement)) {
                PsiJavaCodeReferenceElement ref = (PsiJavaCodeReferenceElement)parent;

                if (psiClass.isValid() && !psiClass.getManager().areElementsEquivalent(psiClass, resolveReference(ref))) {
                    final boolean staticImport = ref instanceof PsiImportStaticReferenceElement;
                    PsiElement newElement;
                    try {
                        newElement = staticImport
                                ? ((PsiImportStaticReferenceElement)ref).bindToTargetClass(psiClass)
                                : ref.bindToElement(psiClass);
                    }
                    catch (IncorrectOperationException e) {
                        return endOffset; // can happen if fqn contains reserved words, for example
                    }

                    final RangeMarker rangeMarker = null; //document.createRangeMarker(newElement.getTextRange());
                    documentManager.doPostponedOperationsAndUnblockDocument(document);
                    documentManager.commitDocument(document);

                    newElement = null;// CodeInsightUtilCore.findElementInRange(file, rangeMarker.getStartOffset(), rangeMarker.getEndOffset(),
//                            PsiJavaCodeReferenceElement.class,
//                            JavaLanguage.INSTANCE);
                    //rangeMarker.dispose();
                    if (newElement != null) {
                        newEndOffset = newElement.getTextRange().getEndOffset();
                        if (!(newElement instanceof PsiReferenceExpression)) {
                            PsiReferenceParameterList parameterList = ((PsiJavaCodeReferenceElement)newElement).getParameterList();
                            if (parameterList != null) {
                                newEndOffset = parameterList.getTextRange().getStartOffset();
                            }
                        }

                        if (!staticImport &&
                                !psiClass.getManager().areElementsEquivalent(psiClass, resolveReference((PsiReference)newElement)) &&
                                !PsiUtil.isInnerClass(psiClass)) {
                            final String qName = psiClass.getQualifiedName();
                            if (qName != null) {
                                document.replaceString(newElement.getTextRange().getStartOffset(), newEndOffset, qName);
                                newEndOffset = newElement.getTextRange().getStartOffset() + qName.length();
                            }
                        }
                    }
                }
            }
        }

        if (toDelete != null && toDelete.isValid()) {
            DocumentUtils.deleteString(document, toDelete.getStartOffset(), toDelete.getEndOffset());
        }

        return newEndOffset;
    }

    public static boolean inSomePackage(PsiElement context) {
        PsiFile contextFile = context.getContainingFile();
        return contextFile instanceof PsiClassOwner && StringUtil.isNotEmpty(((PsiClassOwner)contextFile).getPackageName());
    }

    @Nullable
    public static PsiType getLookupElementType(final LookupElement element) {
        TypedLookupItem typed = element.as(TypedLookupItem.CLASS_CONDITION_KEY);
        return typed != null ? typed.getType() : null;
    }

    @NotNull
    public static <T extends PsiType> T originalize(@NotNull T type) {
        if (!type.isValid()) {
            return type;
        }

        T result = new PsiTypeMapper() {
            private final Set<PsiClassType> myVisited = new ReferenceOpenHashSet<>();

            @Override
            public PsiType visitClassType(@NotNull final PsiClassType classType) {
                if (!myVisited.add(classType)) return classType;

                final PsiClassType.ClassResolveResult classResolveResult = classType.resolveGenerics();
                final PsiClass psiClass = classResolveResult.getElement();
                final PsiSubstitutor substitutor = classResolveResult.getSubstitutor();
                if (psiClass == null) return classType;

                return new PsiImmediateClassType(CompletionUtil.getOriginalOrSelf(psiClass), originalizeSubstitutor(substitutor));
            }

            private PsiSubstitutor originalizeSubstitutor(final PsiSubstitutor substitutor) {
                PsiSubstitutor originalSubstitutor = PsiSubstitutor.EMPTY;
                for (final Map.Entry<PsiTypeParameter, PsiType> entry : substitutor.getSubstitutionMap().entrySet()) {
                    final PsiType value = entry.getValue();
                    originalSubstitutor = originalSubstitutor.put(CompletionUtil.getOriginalOrSelf(entry.getKey()),
                            value == null ? null : mapType(value));
                }
                return originalSubstitutor;
            }


            @Override
            public PsiType visitType(@NotNull PsiType type) {
                return type;
            }
        }.mapType(type);
        if (result == null) {
            throw new AssertionError("Null result for type " + type + " of class " + type.getClass());
        }
        return result;
    }

    public static boolean isInExcludedPackage(@NotNull final PsiMember member, boolean allowInstanceInnerClasses) {
        final String name = PsiUtil.getMemberQualifiedName(member);
        if (name == null) return false;

        if (!member.hasModifierProperty(PsiModifier.STATIC)) {
            if (member instanceof PsiMethod || member instanceof PsiField) {
                return false;
            }
            if (allowInstanceInnerClasses && member instanceof PsiClass && member.getContainingClass() != null) {
                return false;
            }
        }

        return false;
//        return JavaProjectCodeInsightSettings.getSettings(member.getProject()).isExcluded(name);
    }

    public static LinkedHashSet<String> getAllLookupStrings(@NotNull PsiMember member) {
        LinkedHashSet<String> allLookupStrings = new LinkedHashSet<>();
        String name = member.getName();
        allLookupStrings.add(name);
        PsiClass containingClass = member.getContainingClass();
        while (containingClass != null) {
            final String className = containingClass.getName();
            if (className == null) {
                break;
            }
            name = className + "." + name;
            allLookupStrings.add(name);
            final PsiElement parent = containingClass.getParent();
            if (!(parent instanceof PsiClass)) {
                break;
            }
            containingClass = (PsiClass)parent;
        }
        return allLookupStrings;
    }

    @Nullable
    public static RangeMarker insertTemporary(int endOffset, Document document, String temporary) {
        final CharSequence chars = document.getCharsSequence();
        if (endOffset < chars.length() && Character.isJavaIdentifierPart(chars.charAt(endOffset))){
            DocumentUtils.insertString(document, endOffset, temporary);
//            RangeMarkerImpl impl = new RangeMarkerImpl();
//            RangeMarker toDelete = document.createRangeMarker(endOffset, endOffset + 1);
//            toDelete.setGreedyToLeft(true);
//            toDelete.setGreedyToRight(true);
//            return toDelete;
        }
        return null;
        //throw new UnsupportedOperationException("Not yet implemented, inserTemporary()");
    }

    @Nullable
    static PsiElement resolveReference(final PsiReference psiReference) {
        if (psiReference instanceof PsiPolyVariantReference) {
            final ResolveResult[] results = ((PsiPolyVariantReference)psiReference).multiResolve(true);
            if (results.length == 1) return results[0].getElement();
        }
        return psiReference.resolve();
    }

    public static int findQualifiedNameStart(@NotNull InsertionContext context) {
        int start = context.getTailOffset() - 1;
        while (start >= 0) {
            char ch = context.getDocument().getCharsSequence().charAt(start);
            if (!Character.isJavaIdentifierPart(ch) && ch != '.') break;
            start--;
        }
        return start + 1;
    }


    public static boolean isSourceLevelAccessible(@NotNull PsiElement context,
                                                  @NotNull PsiClass psiClass,
                                                  final boolean pkgContext) {
        return isSourceLevelAccessible(context, psiClass, pkgContext, psiClass.getContainingClass());
    }

    private static boolean isSourceLevelAccessible(PsiElement context,
                                                   @NotNull PsiClass psiClass,
                                                   final boolean pkgContext,
                                                   @Nullable PsiClass qualifierClass) {
        if (!JavaPsiFacade.getInstance(psiClass.getProject()).getResolveHelper().isAccessible(psiClass, context, qualifierClass)) {
            return false;
        }

        if (pkgContext) {
            PsiClass topLevel = PsiUtil.getTopLevelClass(psiClass);
            if (topLevel != null) {
                String fqName = topLevel.getQualifiedName();
                if (fqName != null && StringUtil.isEmpty(StringUtil.getPackageName(fqName))) {
                    return false;
                }
            }
        }

        return true;
    }
}
