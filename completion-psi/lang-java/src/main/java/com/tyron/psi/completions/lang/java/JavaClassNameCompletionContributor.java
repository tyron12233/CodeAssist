package com.tyron.psi.completions.lang.java;

import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiElement;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionType;
import com.tyron.psi.completion.InsertHandler;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.patterns.PsiJavaElementPattern;
import com.tyron.psi.editor.Editor;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbAware;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.CommonClassNames;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaModule;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.JBIterable;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class JavaClassNameCompletionContributor extends CompletionContributor implements DumbAware {

    public static final PsiJavaElementPattern.Capture<PsiElement> AFTER_NEW = psiElement().afterLeaf(PsiKeyword.NEW);

    public static void addAllClasses(@NotNull CompletionParameters parameters,
                                     final boolean filterByScope,
                                     @NotNull final PrefixMatcher matcher,
                                     @NotNull final Consumer<? super LookupElement> consumer) {
//        final PsiElement insertedElement = parameters.getPosition();
//        final PsiFile psiFile = insertedElement.getContainingFile();
//
//        JavaClassReference ref = JavaClassReferenceCompletionContributor.findJavaClassReference(psiFile, parameters.getOffset());
//        if (ref != null && ref.getContext() instanceof PsiClass) {
//            return;
//        }
//
//        if (JavaCompletionContributor.getAnnotationNameIfInside(insertedElement) != null) {
//            MultiMap<String, PsiClass> annoMap = getAllAnnotationClasses(insertedElement, matcher);
//            Processor<PsiClass> processor = new LimitedAccessibleClassPreprocessor(parameters, filterByScope, anno -> {
//                JavaPsiClassReferenceElement item = AllClassesGetter.createLookupItem(anno, JAVA_CLASS_INSERT_HANDLER);
//                item.addLookupStrings(getClassNameWithContainers(anno));
//                consumer.consume(item);
//                return true;
//            });
//            for (String name : matcher.sortMatching(annoMap.keySet())) {
//                if (!ContainerUtil.process(annoMap.get(name), processor)) break;
//            }
//            return;
//        }
//
//        final boolean inPermitsList = JavaCompletionContributor.IN_PERMITS_LIST.accepts(insertedElement);
//        final ElementFilter filter = JavaCompletionContributor.getReferenceFilter(insertedElement);
//        if (filter == null) return;
//
//        final boolean inJavaContext = insertedElement instanceof PsiIdentifier;
//        final boolean afterNew = AFTER_NEW.accepts(insertedElement);
//        if (afterNew) {
//            final PsiExpression expr = PsiTreeUtil.getContextOfType(insertedElement, PsiExpression.class, true);
//            for (final ExpectedTypeInfo info : ExpectedTypesProvider.getExpectedTypes(expr, true)) {
//                final PsiType type = info.getType();
//                final PsiClass psiClass = PsiUtil.resolveClassInType(type);
//                if (psiClass != null && psiClass.getName() != null) {
//                    consumer.consume(createClassLookupItem(psiClass, inJavaContext));
//                }
//                final PsiType defaultType = info.getDefaultType();
//                if (!defaultType.equals(type)) {
//                    final PsiClass defClass = PsiUtil.resolveClassInType(defaultType);
//                    if (defClass != null && defClass.getName() != null) {
//                        consumer.consume(createClassLookupItem(defClass, true));
//                    }
//                }
//            }
//        }
//
//        final boolean pkgContext = JavaCompletionUtil.inSomePackage(insertedElement);
//        final Project project = insertedElement.getProject();
//        final GlobalSearchScope scope;
//        if (inPermitsList) {
//            PsiJavaModule javaModule = JavaModuleGraphUtil.findDescriptorByElement(psiFile.getOriginalElement());
//            if (javaModule == null) return;
//            JavaModuleScope moduleScope = JavaModuleScope.moduleScope(javaModule);
//            if (moduleScope == null) return;
//            scope = moduleScope;
//        }
//        else {
//            scope = filterByScope ? psiFile.getResolveScope() : GlobalSearchScope.allScope(project);
//        }
//
//        Processor<PsiClass> classProcessor = new Processor<>() {
//            @Override
//            public boolean process(PsiClass psiClass) {
//                processClass(psiClass, null, "");
//                return true;
//            }
//
//            private void processClass(PsiClass psiClass, @Nullable Set<? super PsiClass> visited, String prefix) {
//                boolean isInnerClass = StringUtil.isNotEmpty(prefix);
//                if (isInnerClass && isProcessedIndependently(psiClass)) {
//                    return;
//                }
//
//                if (filter.isAcceptable(psiClass, insertedElement)) {
//                    if (!inJavaContext) {
//                        JavaPsiClassReferenceElement element = AllClassesGetter.createLookupItem(psiClass, AllClassesGetter.TRY_SHORTENING);
//                        element.setLookupString(prefix + element.getLookupString());
//                        consumer.consume(element);
//                    }
//                    else {
//                        Condition<PsiClass> condition = eachClass ->
//                                filter.isAcceptable(eachClass, insertedElement) &&
//                                        AllClassesGetter.isAcceptableInContext(insertedElement, eachClass, filterByScope, pkgContext);
//                        for (JavaPsiClassReferenceElement element : createClassLookupItems(psiClass, afterNew, JAVA_CLASS_INSERT_HANDLER, condition)) {
//                            element.setLookupString(prefix + element.getLookupString());
//
//                            JavaConstructorCallElement.wrap(element, insertedElement).forEach(
//                                    e -> consumer.consume(JavaCompletionUtil.highlightIfNeeded(null, e, e.getObject(), insertedElement)));
//                        }
//                    }
//                }
//                else {
//                    String name = psiClass.getName();
//                    if (name != null) {
//                        PsiClass[] innerClasses = psiClass.getInnerClasses();
//                        if (innerClasses.length > 0) {
//                            if (visited == null) visited = new HashSet<>();
//
//                            for (PsiClass innerClass : innerClasses) {
//                                if (visited.add(innerClass)) {
//                                    processClass(innerClass, visited, prefix + name + ".");
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//
//            private boolean isProcessedIndependently(PsiClass psiClass) {
//                String innerName = psiClass.getName();
//                return innerName != null && matcher.prefixMatches(innerName);
//            }
//        };
//        AllClassesGetter.processJavaClasses(matcher, project, scope,
//                new LimitedAccessibleClassPreprocessor(parameters, filterByScope, classProcessor));
    }

    @NotNull
    private static MultiMap<String, PsiClass> getAllAnnotationClasses(PsiElement context, PrefixMatcher matcher) {
        MultiMap<String, PsiClass> map = new MultiMap<>();
        GlobalSearchScope scope = context.getResolveScope();
        PsiClass annotation = JavaPsiFacade.getInstance(context.getProject()).findClass(CommonClassNames.JAVA_LANG_ANNOTATION_ANNOTATION, scope);
        if (annotation != null) {
//            DirectClassInheritorsSearch.search(annotation, scope, false).forEach(psiClass -> {
//                if (!psiClass.isAnnotationType() || psiClass.getQualifiedName() == null) return true;
//
//                String name = Objects.requireNonNull(psiClass.getName());
//                if (!matcher.prefixMatches(name)) {
//                    name = getClassNameWithContainers(psiClass);
//                    if (!matcher.prefixMatches(name)) return true;
//                }
//                map.putValue(name, psiClass);
//                return true;
//            });
        }
        return map;
    }

    @NotNull
    private static String getClassNameWithContainers(@NotNull PsiClass psiClass) {
        StringBuilder name = new StringBuilder(Objects.requireNonNull(psiClass.getName()));
        for (PsiClass parent : JBIterable.generate(psiClass, PsiClass::getContainingClass)) {
            name.insert(0, parent.getName() + ".");
        }
        return name.toString();
    }

    public static JavaPsiClassReferenceElement createClassLookupItem(final PsiClass psiClass, final boolean inJavaContext) {
        return AllClassesGetter.createLookupItem(psiClass, inJavaContext ? null
                : AllClassesGetter.TRY_SHORTENING);
    }

    public static List<JavaPsiClassReferenceElement> createClassLookupItems(final PsiClass psiClass,
                                                                            boolean withInners,
                                                                            InsertHandler<JavaPsiClassReferenceElement> insertHandler,
                                                                            Condition<? super PsiClass> condition) {
        List<JavaPsiClassReferenceElement> result = new SmartList<>();
        if (condition.value(psiClass)) {
            result.add(AllClassesGetter.createLookupItem(psiClass, insertHandler));
        }
        String name = psiClass.getName();
        if (withInners && name != null) {
            for (PsiClass inner : psiClass.getInnerClasses()) {
                if (inner.hasModifierProperty(PsiModifier.STATIC)) {
                    for (JavaPsiClassReferenceElement lookupInner : createClassLookupItems(inner, true, insertHandler, condition)) {
                        String forced = lookupInner.getForcedPresentableName();
                        String qualifiedName = name + "." + (forced != null ? forced : inner.getName());
                        lookupInner.setForcedPresentableName(qualifiedName);
                        lookupInner.setLookupString(qualifiedName);
                        result.add(lookupInner);
                    }
                }
            }
        }
        return result;
    }



    public String handleEmptyLookup(@NotNull final CompletionParameters parameters, final Editor editor) {
        if (!(parameters.getOriginalFile() instanceof PsiJavaFile)) return null;

//        if (shouldShowSecondSmartCompletionHint(parameters)) {
//            return LangBundle.message("completion.no.suggestions") +
//                    "; " +
//                    StringUtil.decapitalize(
//                            JavaBundle.message("completion.class.name.hint.2", KeymapUtil.getFirstKeyboardShortcutText(IdeActions.ACTION_CODE_COMPLETION)));
//        }

        return null;
    }

    private static boolean shouldShowSecondSmartCompletionHint(final CompletionParameters parameters) {
        return parameters.getCompletionType() == CompletionType.BASIC &&
                parameters.getInvocationCount() == 2 &&
                parameters.getOriginalFile().getLanguage().isKindOf(JavaLanguage.INSTANCE);
    }
}
