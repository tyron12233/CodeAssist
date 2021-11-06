package com.tyron.psi.completion;

import android.util.Log;

import com.tyron.psi.completion.impl.CompletionServiceImpl;
import com.tyron.psi.completions.lang.java.BasicExpressionCompletionContributor;
import com.tyron.psi.completions.lang.java.JavaClassNameCompletionContributor;
import com.tyron.psi.completions.lang.java.JavaCompletionContributor;
import com.tyron.psi.completions.lang.java.JavaNoVariantsDelegator;
import com.tyron.psi.completions.lang.java.TestCompletionContributor;
import com.tyron.psi.completions.lang.java.guess.GuessManager;
import com.tyron.psi.completions.lang.java.guess.GuessManagerImpl;
import com.tyron.psi.completions.lang.java.scope.JavaCompletionProcessor;
import com.tyron.psi.completions.lang.java.search.PsiShortNamesCache;
import com.tyron.psi.completions.lang.java.search.PsiShortNamesCacheImpl;
import com.tyron.psi.editor.CaretModel;
import com.tyron.psi.editor.Editor;
import com.tyron.psi.lookup.LookupElementPresentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.core.CoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.diagnostic.PluginProblemReporterImpl;
import org.jetbrains.kotlin.com.intellij.lang.ASTNode;
import org.jetbrains.kotlin.com.intellij.lang.injection.InjectedLanguageManager;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.application.WriteAction;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.components.ComponentManager;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.DefaultPluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiResolveHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeEvent;
import org.jetbrains.kotlin.com.intellij.psi.PsiTreeChangeListener;
import org.jetbrains.kotlin.com.intellij.psi.ResolveState;
import org.jetbrains.kotlin.com.intellij.psi.impl.JavaPsiFacadeImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.codeStyle.IndentHelper;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.CompositeElement;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeCopyHandler;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.TreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.scope.util.PsiScopesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.util.Consumer;

import java.util.Collections;
import java.util.EventListener;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public class CompletionEngine {

    private final CoreProjectEnvironment mProjectEnvironment;
    private final CompletionService mCompletionService;
    private final Project mProject;
    private final ReentrantLock mLock = new ReentrantLock();

    volatile int invocationCount = -1;

    private CompletionEnvironment mCompletionEnvironment;

    public void setCompletionEnvironment(CompletionEnvironment environment) {
        mCompletionEnvironment = environment;
    }

    public CompletionEngine(CoreProjectEnvironment environment) {
        mProjectEnvironment = environment;
        mProject = environment.getProject();
        mCompletionService = new CompletionServiceImpl();
        environment.registerProjectExtensionPoint(CompletionContributor.EP, CompletionContributor.class);
        environment.getProject().getExtensionArea().registerExtensionPoint(PsiTreeChangeListener.EP.getName(), PsiTreeChangeListener.class.getName(), ExtensionPoint.Kind.INTERFACE);
        environment.getEnvironment().registerApplicationService(CompletionService.class, mCompletionService);
//        nvironment.getEnvironment().registerApplicationService(IndentHelper.class, new IndentHelper() {
//
//            public static final int TOO_BIG_WALK_THRESHOLD = 450;
//            public static final int INDENT_FACTOR = 10000; // "indent" is indent_level * INDENT_FACTOR + spaces
//
//            @Override
//            public int getIndent(@NotNull PsiFile psiFile, @NotNull ASTNode astNode) {
//                return getIndent(psiFile, astNode, false);
//            }
//
//            @Override
//            public int getIndent(@NotNull PsiFile psiFile, @NotNull ASTNode astNode, boolean b) {
//                return getIndentInner(psiFile.getProject(), psiFile.getFileType(), astNode, true, 0);
//            }
//
//            protected int getIndentInner(Project project, FileType fileType, final ASTNode element, boolean includeNonSpace, int recursionLevel) {
//                if (recursionLevel > TOO_BIG_WALK_THRESHOLD) return 0;
//
//                if (element.getTreePrev() != null) {
//                    ASTNode prev = element.getTreePrev();
//                    ASTNode lastCompositePrev;
//                    while (prev instanceof CompositeElement && !TreeUtil.isStrongWhitespaceHolder(prev.getElementType())) {
//                        lastCompositePrev = prev;
//                        prev = prev.getLastChildNode();
//                        if (prev == null) { // element.prev is "empty composite"
//                            return getIndentInner(project, fileType, lastCompositePrev, includeNonSpace, recursionLevel + 1);
//                        }
//                    }
//
//                    String text = prev.getText();
//                    int index = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));
//
//                    if (index >= 0) {
//                        return getIndent(project, fileType, text.substring(index + 1), includeNonSpace);
//                    }
//
//                    if (includeNonSpace) {
//                        return getIndentInner(project, fileType, prev, includeNonSpace, recursionLevel + 1) + getIndent(project, fileType, text, includeNonSpace);
//                    }
//
//
//                    ASTNode parent = prev.getTreeParent();
//                    ASTNode child = prev;
//                    while (parent != null) {
//                        if (child.getTreePrev() != null) break;
//                        child = parent;
//                        parent = parent.getTreeParent();
//                    }
//
//                    if (parent == null) {
//                        return getIndent(project, fileType, text, includeNonSpace);
//                    }
//                    else {
//                        return getIndentInner(project, fileType, prev, includeNonSpace, recursionLevel + 1);
//                    }
//                }
//                else {
//                    if (element.getTreeParent() == null) {
//                        return 0;
//                    }
//                    return getIndentInner(project, fileType, element.getTreeParent(), includeNonSpace, recursionLevel + 1);
//                }
//            }
//
//            public int getIndent(Project project, FileType fileType, String text, boolean includeNonSpace) {
//                //   final CodeStyleSettings settings = CodeStyleSettingsManager.getSettings(project);
//                int i;
//                for (i = text.length() - 1; i >= 0; i--) {
//                    char c = text.charAt(i);
//                    if (c == '\n' || c == '\r') break;
//                }
//                i++;
//
//                int spaceCount = 0;
//                int tabCount = 0;
//                for (int j = i; j < text.length(); j++) {
//                    char c = text.charAt(j);
//                    if (c != '\t') {
//                        if (!includeNonSpace && c != ' ') break;
//                        spaceCount++;
//                    }
//                    else {
//                        tabCount++;
//                    }
//                }
//
//                if (tabCount == 0) return spaceCount;
//
//                int tabSize = 4;//settings.getTabSize(fileType);
//                int indentSize = 1;//settings.getIndentSize(fileType);
//                if (indentSize <= 0) {
//                    indentSize = 1;
//                }
//                int indentLevel = tabCount * tabSize / indentSize;
//                return indentLevel * INDENT_FACTOR + spaceCount;
//            }
//        });
        environment.getEnvironment().registerApplicationService(PluginProblemReporterImpl.getInterface(), new PluginProblemReporterImpl());
        //environment.getEnvironment().getApplication().getExtensionArea().registerExtensionPoint(TreeCopyHandler.EP_NAME.getName(), TreeCopyHandler.class.getName(), ExtensionPoint.Kind.INTERFACE);
        environment.getProject().registerService(GuessManager.class, new GuessManagerImpl(mProject));
        environment.getProject().registerService(PsiNameHelper.class, PsiNameHelperImpl.getInstance());
        environment.getProject().registerService(PsiShortNamesCache.class, new PsiShortNamesCacheImpl(mProject));

//        CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new TestCompletionContributor());
        CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaCompletionContributor());
//        CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaClassNameCompletionContributor());
//        CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaNoVariantsDelegator());
      //  CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new BasicExpressionCompletionContributor());
    }

    public void complete(PsiJavaFile file, PsiElement element, int offset, Consumer<CompletionResult> consumer) {
        mLock.lock();
        mCompletionEnvironment.compileJavaFiles(Collections.singletonList(file), Collections.emptyList());
        try {
            CompletionParameters completionParameters = new CompletionParameters(element, file, CompletionType.BASIC, offset, invocationCount++, new Editor() {
                @Override
                public Document getDocument() {
                    return Objects.requireNonNull(file.getViewProvider().getDocument());
                }

                @Override
                public CaretModel getCaretModel() {
                    return null;
                }

                @Override
                public Project getProject() {
                    return mProjectEnvironment.getProject();
                }

                @Override
                public boolean isViewer() {
                    return false;
                }

                @Override
                public <T> T getUserData(@NotNull Key<T> key) {
                    return null;
                }

                @Override
                public <T> void putUserData(@NotNull Key<T> key, @Nullable T t) {

                }
            }, () -> true);
            mCompletionService.performCompletion(completionParameters, consumer);
        } catch (Throwable e) {
          Log.e("Completion", "Unable to complete", e);
        } finally {
            mLock.unlock();
        }
    }
}
