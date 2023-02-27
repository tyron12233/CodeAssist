package org.jetbrains.kotlin.com.intellij.psi.stubs;

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.jetbrains.kotlin.com.intellij.diagnostic.PluginException;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageParserDefinitions;
import org.jetbrains.kotlin.com.intellij.lang.ParserDefinition;
import org.jetbrains.kotlin.com.intellij.lang.TreeBackedLighterAST;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.ControlFlowException;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.kotlin.com.intellij.openapi.roots.impl.PushedFilePropertiesRetriever;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Trinity;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.FileViewProvider;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.StubBuilder;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IFileElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IStubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.ExceptionUtil;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexedFile;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingDataKeys;
import org.jetbrains.kotlin.com.intellij.util.indexing.PsiDependentFileContent;

public final class StubTreeBuilder {
    private static final Key<Stub> stubElementKey = Key.create("stub.tree.for.file.content");

    private StubTreeBuilder() {
    }

    static boolean requiresContentToFindBuilder(@NonNull FileType fileType) {
        BinaryFileStubBuilder builder = BinaryFileStubBuilders.INSTANCE.forFileType(fileType);
        return builder instanceof BinaryFileStubBuilder.CompositeBinaryFileStubBuilder<?>;
    }

    public static StubBuilderType getStubBuilderType(@NonNull IndexedFile file, boolean toBuild) {
        FileType fileType = file.getFileType();
        BinaryFileStubBuilder builder = BinaryFileStubBuilders.INSTANCE.forFileType(fileType);
        if (builder != null) {
            if (builder instanceof BinaryFileStubBuilder.CompositeBinaryFileStubBuilder<?>) {
                Object subBuilder = ((BinaryFileStubBuilder.CompositeBinaryFileStubBuilder<?>)builder).getSubBuilder((FileContent) file);
                return new StubBuilderType((BinaryFileStubBuilder.CompositeBinaryFileStubBuilder<?>)builder, subBuilder);
            } else {
                return new StubBuilderType(builder);
            }
        }

        if (fileType instanceof LanguageFileType) {
            Language l = ((LanguageFileType)fileType).getLanguage();
            ParserDefinition parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(l);
            if (parserDefinition == null) {
                return null;
            }

            IFileElementType elementType = parserDefinition.getFileNodeType();
            if (!(elementType instanceof IStubFileElementType)) return null;
            VirtualFile vFile = file.getFile();
            boolean shouldBuildStubFor = ((IStubFileElementType<?>)elementType).shouldBuildStubFor(vFile);
            if (toBuild && !shouldBuildStubFor) return null;
            PushedFilePropertiesRetriever pushedFilePropertiesRetriever = PushedFilePropertiesRetriever.getInstance();
            @NonNull List<String> properties = pushedFilePropertiesRetriever != null
                    ? pushedFilePropertiesRetriever.dumpSortedPushedProperties(vFile)
                    : Collections.emptyList();
            return new StubBuilderType((IStubFileElementType<?>)elementType,  properties);
        }

        return null;
    }

    public static @Nullable Stub buildStubTree(@NonNull FileContent inputData) {
        StubBuilderType type = getStubBuilderType(inputData, false);
        return type == null ? null : buildStubTree(inputData, type);
    }

    private static <T> T handleStubBuilderException(@NonNull FileContent inputData,
                                                              @NonNull StubBuilderType stubBuilderType,
                                                              @NonNull ThrowableComputable<T, Exception> builder) {
        try {
            return builder.compute();
        }
        catch (CancellationException ce) {
            throw ce;
        }
        catch (Exception e) {
            if (e instanceof ControlFlowException) ExceptionUtil.rethrowUnchecked(e);
            Logger.getInstance(StubTreeBuilder.class).error(PluginException.createByClass("Failed to build stub tree for " + inputData.getFileName(), e,
                    stubBuilderType.getClass()));
            return null;
        }
    }

    @Nullable
    public static Stub buildStubTree(@NonNull FileContent inputData, @NonNull StubBuilderType stubBuilderType) {
        Stub data = inputData.getUserData(stubElementKey);
        if (data != null) return data;

        synchronized (inputData) {
            data = inputData.getUserData(stubElementKey);
            if (data != null) return data;

            BinaryFileStubBuilder builder = stubBuilderType.getBinaryFileStubBuilder();
            if (builder != null) {
                data = handleStubBuilderException(inputData, stubBuilderType, () -> builder.buildStubTree(inputData));
                if (data instanceof PsiFileStubImpl && !((PsiFileStubImpl<?>)data).rootsAreSet()) {
                    ((PsiFileStubImpl<?>)data).setStubRoots(new PsiFileStub[]{(PsiFileStubImpl<?>)data});
                }
            }
            else {
                CharSequence contentAsText = inputData.getContentAsText();
                PsiDependentFileContent fileContent = (PsiDependentFileContent)inputData;
                FileViewProvider viewProvider = fileContent.getPsiFile().getViewProvider();
                PsiFile psi = viewProvider.getStubBindingRoot();
                // if we load AST, it should be easily gc-able. See PsiFileImpl.createTreeElementPointer()
                psi.putUserData(IndexingDataKeys.FILE_TEXT_CONTENT_KEY, contentAsText);
                StubElement built = null;
                try {
                    IStubFileElementType<?> stubFileElementType = ((PsiFileImpl)psi).getElementTypeForStubBuilder();
                    if (stubFileElementType != null) {
                        StubBuilder stubBuilder = stubFileElementType.getBuilder();
                        if (stubBuilder instanceof LightStubBuilder) {
                            LightStubBuilder.FORCED_AST.set(fileContent.getLighterAST());
                        }
                        built = handleStubBuilderException(inputData, stubBuilderType, () -> stubBuilder.buildStubTree(psi));
                        List<Pair<IStubFileElementType, PsiFile>> stubbedRoots = getStubbedRoots(viewProvider);
                        List<PsiFileStub<?>> stubs = new ArrayList<>(stubbedRoots.size());
                        stubs.add((PsiFileStub<?>)built);

                        for (Pair<IStubFileElementType, PsiFile> stubbedRoot : stubbedRoots) {
                            PsiFile secondaryPsi = stubbedRoot.second;
                            if (psi == secondaryPsi) continue;
                            StubBuilder stubbedRootBuilder = stubbedRoot.first.getBuilder();
                            if (stubbedRootBuilder instanceof LightStubBuilder) {
                                LightStubBuilder.FORCED_AST.set(new TreeBackedLighterAST(secondaryPsi.getNode()));
                            }
                            StubElement<?> element = handleStubBuilderException(inputData, stubBuilderType, () -> stubbedRootBuilder.buildStubTree(secondaryPsi));
                            if (element instanceof PsiFileStub) {
                                stubs.add((PsiFileStub<?>)element);
                            }
                            ensureNormalizedOrder(element);
                        }
                        PsiFileStub<?>[] stubsArray = stubs.toArray(PsiFileStub.EMPTY_ARRAY);
                        for (PsiFileStub<?> stub : stubsArray) {
                            if (stub instanceof PsiFileStubImpl) {
                                ((PsiFileStubImpl<?>)stub).setStubRoots(stubsArray);
                            }
                        }
                    }
                }
                finally {
                    psi.putUserData(IndexingDataKeys.FILE_TEXT_CONTENT_KEY, null);
                }
                data = built;
            }

            ensureNormalizedOrder(data);
            inputData.putUserData(stubElementKey, data);
            return data;
        }
    }

    private static void ensureNormalizedOrder(Stub element) {
        if (element instanceof StubBase) {
            ((StubBase) element).myStubList.finalizeLoadingStage();
        }

    }

    public static @NonNull List<Pair<IStubFileElementType, PsiFile>> getStubbedRoots(@NonNull FileViewProvider viewProvider) {

        List<Trinity<Language, IStubFileElementType, PsiFile>> roots = new SmartList<>();
        PsiFile stubBindingRoot = viewProvider.getStubBindingRoot();

        for (Language language : viewProvider.getLanguages()) {
            PsiFile file = viewProvider.getPsi(language);
            if (file instanceof PsiFileImpl) {
                IElementType type = ((PsiFileImpl) file).getElementTypeForStubBuilder();
                if (type != null) {
                    roots.add(Trinity.create(language, (IStubFileElementType) type, file));
                }
            }
        }

        ContainerUtil.sort(roots, (o1, o2) -> {
            if (o1.third == stubBindingRoot) {
                return o2.third == stubBindingRoot ? 0 : -1;
            } else {
                return o2.third ==
                       stubBindingRoot ? 1 : StringUtil.compare(((Language) o1.first).getID(),
                        ((Language) o2.first).getID(),
                        false);
            }
        });


        return ContainerUtil.map(roots, (trinity) -> Pair.create((IStubFileElementType) trinity.second, (PsiFile) trinity.third));
    }
}
