package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionService;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.PairConsumer;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.*;

/**
 * @author peter
 */
public abstract class StaticMemberProcessor {
    private final Set<PsiClass> myStaticImportedClasses = new HashSet<>();
    private final PsiElement myPosition;
    private final Project myProject;
    private final PsiResolveHelper myResolveHelper;
    private boolean myHintShown;
    private final boolean myPackagedContext;

    public StaticMemberProcessor(final PsiElement position) {
        myPosition = position;
        myProject = myPosition.getProject();
        myResolveHelper = JavaPsiFacade.getInstance(myProject).getResolveHelper();
        myPackagedContext = JavaCompletionUtil.inSomePackage(position);
    }

    public void importMembersOf(@Nullable PsiClass psiClass) {
        ContainerUtil.addIfNotNull(myStaticImportedClasses, psiClass);
    }

    public void processStaticMethodsGlobally(final PrefixMatcher matcher, Consumer<? super LookupElement> consumer) {
        final GlobalSearchScope scope = myPosition.getResolveScope();
//        Collection<String> memberNames = JavaStaticMemberNameIndex.getInstance().getAllKeys(myProject);
//        for (final String memberName : matcher.sortMatching(memberNames)) {
//            Set<PsiClass> classes = new HashSet<>();
//            for (final PsiMember member : JavaStaticMemberNameIndex.getInstance().getStaticMembers(memberName, myProject, scope)) {
//                if (isStaticallyImportable(member)) {
//                    final PsiClass containingClass = member.getContainingClass();
//                    assert containingClass != null : member.getName() + "; " + member + "; " + member.getClass();
//
//                    if (JavaCompletionUtil.isSourceLevelAccessible(myPosition, containingClass, myPackagedContext)) {
//                        if (member instanceof PsiMethod && !classes.add(containingClass)) continue;
//
//                        final boolean shouldImport = myStaticImportedClasses.contains(containingClass);
//                        showHint(shouldImport);
//                        LookupElement item = member instanceof PsiMethod ? createItemWithOverloads((PsiMethod)member, containingClass, shouldImport) :
//                                member instanceof PsiField ? createLookupElement(member, containingClass, shouldImport) :
//                                        null;
//                        if (item != null) consumer.consume(item);
//                    }
//                }
//            }
//        }
    }

    @Nullable
    private LookupElement createItemWithOverloads(PsiMethod method, PsiClass containingClass, boolean shouldImport) {
        List<PsiMethod> overloads = ContainerUtil.findAll(containingClass.findMethodsByName(method.getName(), true),
                this::isStaticallyImportable);

        assert !overloads.isEmpty();
        if (overloads.size() == 1) {
            assert method == overloads.get(0);
            return createLookupElement(method, containingClass, shouldImport);
        }

        if (overloads.get(0).getParameterList().isEmpty()) {
            overloads.add(0, overloads.remove(1));
        }
        return createLookupElement(overloads, containingClass, shouldImport);
    }

    private void showHint(boolean shouldImport) {
        if (!myHintShown && !shouldImport) {
            final String shortcut = "shortcut"; //KeymapUtil.getFirstKeyboardShortcutText(IdeActions.ACTION_SHOW_INTENTION_ACTIONS);
            if (StringUtil.isNotEmpty(shortcut)) {
                CompletionService.getCompletionService().setAdvertisementText(
                        "JavaBundle.message(\"to.import.a.method.statically.press.0\", shortcut)");
            }
            myHintShown = true;
        }
    }

    public List<PsiMember> processMembersOfRegisteredClasses(Condition<? super String> nameCondition, PairConsumer<? super PsiMember, ? super PsiClass> consumer) {
        final ArrayList<PsiMember> result = new ArrayList<>();
        for (final PsiClass psiClass : myStaticImportedClasses) {
            for (final PsiMethod method : psiClass.getAllMethods()) {
                if (nameCondition.value(method.getName())) {
                    if (isStaticallyImportable(method)) {
                        consumer.consume(method, psiClass);
                    }
                }
            }
            for (final PsiField field : psiClass.getAllFields()) {
                if (nameCondition.value(field. getName())) {
                    if (isStaticallyImportable(field)) {
                        consumer.consume(field, psiClass);
                    }
                }
            }
        }
        return result;
    }


    private boolean isStaticallyImportable(final PsiMember member) {
        return member.hasModifierProperty(PsiModifier.STATIC) && isAccessible(member); //&&!StaticImportMemberFix.isExcluded(member);
    }

    public PsiElement getPosition() {
        return myPosition;
    }

    protected boolean isAccessible(PsiMember member) {
        return myResolveHelper.isAccessible(member, myPosition, null);
    }

    @Nullable
    protected abstract LookupElement createLookupElement(@NotNull PsiMember member, @NotNull PsiClass containingClass, boolean shouldImport);

    protected abstract LookupElement createLookupElement(@NotNull List<? extends PsiMethod> overloads, @NotNull PsiClass containingClass, boolean shouldImport);
}