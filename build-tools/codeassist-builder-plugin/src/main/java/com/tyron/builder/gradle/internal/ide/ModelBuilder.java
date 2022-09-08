package com.tyron.builder.gradle.internal.ide;

import static com.android.AndroidProjectTypes.PROJECT_TYPE_APP;
import static com.android.AndroidProjectTypes.PROJECT_TYPE_DYNAMIC_FEATURE;
import static com.android.SdkConstants.DIST_URI;
import static com.tyron.builder.gradle.internal.scope.InternalArtifactType.AIDL_SOURCE_OUTPUT_DIR;
import static com.tyron.builder.gradle.internal.scope.InternalArtifactType.DATA_BINDING_BASE_CLASS_SOURCE_OUT;
import static com.tyron.builder.gradle.internal.scope.InternalArtifactType.JAVAC;
import static com.tyron.builder.gradle.internal.scope.InternalArtifactType.RENDERSCRIPT_SOURCE_OUTPUT_DIR;
import static com.tyron.builder.model.AndroidProject.ARTIFACT_MAIN;

import com.android.SdkConstants;
import com.android.Version;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.artifact.impl.ArtifactsImpl;
import com.tyron.builder.api.dsl.ApplicationExtension;
import com.tyron.builder.api.variant.impl.HasAndroidTest;
import com.tyron.builder.gradle.BaseExtension;
import com.tyron.builder.gradle.internal.BuildTypeData;
import com.tyron.builder.gradle.internal.DefaultConfigData;
import com.tyron.builder.gradle.internal.ExtraModelInfo;
import com.tyron.builder.gradle.internal.ProductFlavorData;
import com.tyron.builder.gradle.internal.TaskManager;
import com.tyron.builder.gradle.internal.component.AndroidTestCreationConfig;
import com.tyron.builder.gradle.internal.component.ApkCreationConfig;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig;
import com.tyron.builder.gradle.internal.component.NestedComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.UnitTestCreationConfig;
import com.tyron.builder.gradle.internal.component.VariantCreationConfig;
import com.tyron.builder.gradle.internal.core.VariantSources;
import com.tyron.builder.gradle.internal.dsl.BuildType;
import com.tyron.builder.gradle.internal.dsl.DefaultConfig;
import com.tyron.builder.gradle.internal.dsl.ProductFlavor;
//import com.tyron.builder.gradle.internal.dsl.TestOptions;
import com.tyron.builder.gradle.internal.errors.SyncIssueReporterImpl;
import com.tyron.builder.gradle.internal.ide.dependencies.ArtifactCollectionsInputs;
import com.tyron.builder.gradle.internal.ide.dependencies.ArtifactCollectionsInputsImpl;
import com.tyron.builder.gradle.internal.ide.dependencies.BuildMappingUtils;
import com.tyron.builder.gradle.internal.ide.dependencies.DependencyGraphBuilder;
import com.tyron.builder.gradle.internal.ide.dependencies.DependencyGraphBuilderKt;
import com.tyron.builder.gradle.internal.ide.dependencies.Level1DependencyModelBuilder;
import com.tyron.builder.gradle.internal.ide.dependencies.Level2DependencyModelBuilder;
import com.tyron.builder.gradle.internal.ide.dependencies.LibraryDependencyCacheBuildService;
import com.tyron.builder.gradle.internal.ide.dependencies.LibraryUtils;
import com.tyron.builder.gradle.internal.ide.level2.EmptyDependencyGraphs;
import com.tyron.builder.gradle.internal.ide.level2.GlobalLibraryMapImpl;
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts;
import com.tyron.builder.gradle.internal.scope.InternalArtifactType;
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer;
import com.tyron.builder.gradle.internal.scope.ProjectInfo;
import com.tyron.builder.gradle.internal.services.BuildServicesKt;
import com.tyron.builder.gradle.internal.tasks.AnchorTaskNames;
//import com.tyron.builder.gradle.internal.tasks.DeviceProviderInstrumentTestTask;
import com.tyron.builder.gradle.internal.tasks.ExportConsumerProguardFilesTask;
import com.tyron.builder.gradle.internal.variant.VariantInputModel;
import com.tyron.builder.gradle.internal.variant.VariantModel;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.gradle.options.ProjectOptionService;
import com.tyron.builder.gradle.options.ProjectOptions;
import com.tyron.builder.compiling.BuildConfigType;
import com.tyron.builder.core.BuilderConstants;
import com.tyron.builder.core.ComponentType;
import com.tyron.builder.core.ComponentTypeImpl;
import com.tyron.builder.core.DefaultManifestParser;
import com.tyron.builder.core.ManifestAttributeSupplier;
import com.tyron.builder.errors.IssueReporter;
import com.tyron.builder.errors.IssueReporter.Type;
import com.tyron.builder.model.AaptOptions;
import com.tyron.builder.model.AndroidArtifact;
import com.tyron.builder.model.AndroidGradlePluginProjectFlags;
import com.tyron.builder.model.AndroidProject;
import com.tyron.builder.model.ArtifactMetaData;
import com.tyron.builder.model.BaseArtifact;
import com.tyron.builder.model.BuildTypeContainer;
import com.tyron.builder.model.CodeShrinker;
import com.tyron.builder.model.Dependencies;
import com.tyron.builder.model.DependenciesInfo;
import com.tyron.builder.model.InstantRun;
import com.tyron.builder.model.JavaArtifact;
import com.tyron.builder.model.LintOptions;
import com.tyron.builder.model.ModelBuilderParameter;
import com.tyron.builder.model.ProductFlavorContainer;
import com.tyron.builder.model.ProjectSyncIssues;
import com.tyron.builder.model.SigningConfig;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.SyncIssue;
import com.tyron.builder.model.TestOptions;
import com.tyron.builder.model.TestedTargetVariant;
import com.tyron.builder.model.Variant;
import com.tyron.builder.model.VariantBuildInformation;
import com.tyron.builder.model.ViewBindingOptions;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.tyron.builder.model.level2.GlobalLibraryMap;
import com.android.utils.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.tyron.builder.plugin.options.SyncOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.openjdk.javax.xml.namespace.QName;
import org.openjdk.javax.xml.stream.XMLEventReader;
import org.openjdk.javax.xml.stream.XMLInputFactory;
import org.openjdk.javax.xml.stream.XMLStreamException;
import org.openjdk.javax.xml.stream.events.Attribute;
import org.openjdk.javax.xml.stream.events.EndElement;
import org.openjdk.javax.xml.stream.events.StartElement;
import org.openjdk.javax.xml.stream.events.XMLEvent;
import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactCollection;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.services.BuildServiceRegistry;
import org.gradle.tooling.provider.model.ParameterizedToolingModelBuilder;
import org.jetbrains.annotations.NotNull;

/** Builder for the custom Android model. */
public class ModelBuilder<Extension extends BaseExtension>
        implements ParameterizedToolingModelBuilder<ModelBuilderParameter> {

    @NonNull private final Project project;
    @NonNull protected final Extension extension;
    @NonNull private final ExtraModelInfo extraModelInfo;
    @NonNull private final VariantModel variantModel;
    private int modelLevel = AndroidProject.MODEL_LEVEL_LATEST;
    private boolean modelWithFullDependency = false;

    /**
     * a map that goes from build name ({@link BuildIdentifier#getName()} to the root dir of the
     * build.
     */
    private ImmutableMap<String, String> buildMapping = null;

    public ModelBuilder(
            @NonNull Project project,
            @NonNull VariantModel variantModel,
            @NonNull Extension extension,
            @NonNull ExtraModelInfo extraModelInfo) {
        this.project = project;
        this.extension = extension;
        this.extraModelInfo = extraModelInfo;
        this.variantModel = variantModel;
    }

    @Override
    public boolean canBuild(@NonNull String modelName) {
        // The default name for a model is the name of the Java interface.
        return modelName.equals(AndroidProject.class.getName())
                || modelName.equals(GlobalLibraryMap.class.getName())
                || modelName.equals(Variant.class.getName())
                || modelName.equals(ProjectSyncIssues.class.getName());
    }

    @NonNull
    @Override
    public Object buildAll(@NonNull String modelName, @NonNull Project project) {
        // build a map from included build name to rootDir (as rootDir is the only thing
        // that we have access to on the tooling API side).
        initBuildMapping(project);

        if (modelName.equals(AndroidProject.class.getName())) {
            return buildAndroidProject(project, true);
        }
        if (modelName.equals(Variant.class.getName())) {
            throw new RuntimeException(
                    "Please use parameterized tooling API to obtain Variant model.");
        }
        return buildNonParameterizedModels(modelName);
    }

    // Build parameterized model. This method is invoked if model is obtained by
    // BuildController::findModel(Model var1, Class<T> var2, Class<P> var3, Action<? super P> var4).
    @NonNull
    @Override
    public Object buildAll(
            @NonNull String modelName,
            @NonNull ModelBuilderParameter parameter,
            @NonNull Project project) {
        // Prevents parameter interface evolution from breaking the model builder.
        parameter = new FailsafeModelBuilderParameter(parameter);

        // build a map from included build name to rootDir (as rootDir is the only thing
        // that we have access to on the tooling API side).
        initBuildMapping(project);
        if (modelName.equals(AndroidProject.class.getName())) {
            return buildAndroidProject(project, parameter.getShouldBuildVariant());
        }
        if (modelName.equals(Variant.class.getName())) {
            return buildVariant(
                    project, parameter.getVariantName(), parameter.getShouldGenerateSources());
        }
        return buildNonParameterizedModels(modelName);
    }

    @NonNull
    private Object buildNonParameterizedModels(@NonNull String modelName) {
        if (modelName.equals(GlobalLibraryMap.class.getName())) {
            return buildGlobalLibraryMap(project.getGradle().getSharedServices());
        } else if (modelName.equals(ProjectSyncIssues.class.getName())) {
            return buildProjectSyncIssuesModel();
        }

        throw new RuntimeException("Invalid model requested: " + modelName);
    }

    @Override
    @NonNull
    public Class<ModelBuilderParameter> getParameterType() {
        return ModelBuilderParameter.class;
    }

    private static Object buildGlobalLibraryMap(
            @NonNull BuildServiceRegistry buildServiceRegistry) {
        LibraryDependencyCacheBuildService libraryDependencyCacheBuildService =
                BuildServicesKt.getBuildService(
                                buildServiceRegistry, LibraryDependencyCacheBuildService.class)
                        .get();
        return new GlobalLibraryMapImpl(libraryDependencyCacheBuildService.getGlobalLibMap());
    }

    private Object buildProjectSyncIssuesModel() {
        variantModel.getSyncIssueReporter().lockHandler();

        ImmutableSet.Builder<SyncIssue> allIssues = ImmutableSet.builder();
        allIssues.addAll(variantModel.getSyncIssueReporter().getSyncIssues());
        allIssues.addAll(
                BuildServicesKt.getBuildService(
                                project.getGradle().getSharedServices(),
                                SyncIssueReporterImpl.GlobalSyncIssueService.class)
                        .get()
                        .getAllIssuesAndClear());
        return new DefaultProjectSyncIssues(allIssues.build());
    }

    /** Indicates the dimensions used for a variant */
    static class DimensionInformation {

        Set<String> buildTypes;
        Set<Pair<String, String>> flavors;

        DimensionInformation(Set<String> buildTypes, Set<Pair<String, String>> flavors) {
            this.buildTypes = buildTypes;
            this.flavors = flavors;
        }

        Boolean isNotEmpty() {
            return !buildTypes.isEmpty() || !flavors.isEmpty();
        }

        static DimensionInformation createFrom(
                Collection<? extends ComponentCreationConfig> components) {
            Set<String> buildTypes = new HashSet<>();
            Set<Pair<String, String>> flavors = new HashSet<>();

            for (ComponentCreationConfig component : components) {
                if (component.getBuildType() != null) {
                    buildTypes.add(component.getBuildType());
                }
                flavors.addAll(
                        component.getProductFlavors().stream()
                                .map(pair -> Pair.of(pair.getFirst(), pair.getSecond()))
                                .collect(Collectors.toList()));
            }
            return new DimensionInformation(buildTypes, flavors);
        }
    }

    private Object buildAndroidProject(Project project, boolean shouldBuildVariant) {
        // Cannot be injected, as the project might not be the same as the project used to construct
        // the model builder e.g. when lint explicitly builds the model.
        ProjectOptionService optionService =
                BuildServicesKt.getBuildService(
                                project.getGradle().getSharedServices(), ProjectOptionService.class)
                        .get();
        ProjectOptions projectOptions = optionService.getProjectOptions();
        Integer modelLevelInt = SyncOptions.buildModelOnlyVersion(projectOptions);
        if (modelLevelInt != null) {
            modelLevel = modelLevelInt;
        }

        if (modelLevel < AndroidProject.MODEL_LEVEL_3_VARIANT_OUTPUT_POST_BUILD) {
            throw new RuntimeException(
                    "This Gradle plugin requires a newer IDE able to request IDE model level 3. For Android Studio this means version 3.0+");
        }

//        StudioVersions.verifyIDEIsNotOld(projectOptions);

        modelWithFullDependency =
                projectOptions.get(BooleanOption.IDE_BUILD_MODEL_FEATURE_FULL_DEPENDENCIES);

        // Get the boot classpath. This will ensure the target is configured.
        List<String> bootClasspath;
        if (variantModel.getVersionedSdkLoader().get().getSdkSetupCorrectly().get()) {
            bootClasspath =
                    variantModel.getFilteredBootClasspath().get().stream()
                            .map(it -> it.getAsFile().getAbsolutePath())
                            .collect(Collectors.toList());
        } else {
            // SDK not set up, error will be reported as a sync issue.
            bootClasspath = Collections.emptyList();
        }
        List<File> frameworkSource = Collections.emptyList();

        // List of extra artifacts, with all test variants added.
        List<ArtifactMetaData> artifactMetaDataList = Lists.newArrayList(
                extraModelInfo.getExtraArtifacts());

        for (ComponentType componentType : ComponentType.Companion.getTestComponents()) {
            artifactMetaDataList.add(
                    new ArtifactMetaDataImpl(
                            componentType.getArtifactName(),
                            true /*isTest*/,
                            componentType.getArtifactType()));
        }

        LintOptions lintOptions = new LintOptionsImpl();
//                ConvertersKt.convertLintOptions(extension.getLintOptions());

        AaptOptions aaptOptions = AaptOptionsImpl.create(extension.getAaptOptions());

        boolean viewBinding =
                variantModel.getVariants().stream()
                        .anyMatch(
                                variantProperties ->
                                        variantProperties.getBuildFeatures().getViewBinding());

        ViewBindingOptions viewBindingOptions = new ViewBindingOptionsImpl(viewBinding);

        DependenciesInfo dependenciesInfo = null;
        if (extension instanceof ApplicationExtension) {
            ApplicationExtension applicationExtension = (ApplicationExtension) extension;
            boolean inApk = applicationExtension.getDependenciesInfo().getIncludeInApk();
            boolean inBundle = applicationExtension.getDependenciesInfo().getIncludeInBundle();
            dependenciesInfo = new DependenciesInfoImpl(inApk, inBundle);
        }

        List<String> flavorDimensionList =
                extension.getFlavorDimensionList() != null
                        ? ImmutableList.copyOf(extension.getFlavorDimensionList())
                        : Lists.newArrayList();

        final VariantInputModel<
                        DefaultConfig,
                        BuildType,
                        ProductFlavor,
                        com.tyron.builder.gradle.internal.dsl.SigningConfig>
                variantInputs = variantModel.getInputs();

        // compute for each main, androidTest, unitTest and testFixtures which buildType and flavors
        // they applied to. This will allow excluding from the model sourcesets that are not
        // used by any of them.
        // Not doing this is confusing to users as they see folders marked as source that aren't
        // used by anything.
        DimensionInformation variantDimensionInfo =
                DimensionInformation.createFrom(variantModel.getVariants());
        DimensionInformation androidTests =
                DimensionInformation.createFrom(
                        variantModel.getTestComponents().stream()
                                .filter(it -> it instanceof AndroidTestCreationConfig)
                                .map(it -> (AndroidTestCreationConfig) it)
                                .collect(Collectors.toList()));
        DimensionInformation unitTests =
                DimensionInformation.createFrom(
                        variantModel.getTestComponents().stream()
                                .filter(it -> it instanceof UnitTestCreationConfig)
                                .map(it -> (UnitTestCreationConfig) it)
                                .collect(Collectors.toList()));

        DefaultConfigData<DefaultConfig> defaultConfigData = variantInputs.getDefaultConfigData();
        ProductFlavorContainer defaultConfig =
                ProductFlavorContainerImpl.createProductFlavorContainer(
                        defaultConfigData,
                        defaultConfigData.getDefaultConfig(),
                        variantDimensionInfo.isNotEmpty() /*includeProdSourceSet*/,
                        androidTests.isNotEmpty() /*includeAndroidTest*/,
                        unitTests.isNotEmpty() /*includeUnitTest*/,
                        extraModelInfo.getExtraFlavorSourceProviders(BuilderConstants.MAIN));

        Collection<BuildTypeContainer> buildTypes = Lists.newArrayList();
        Collection<ProductFlavorContainer> productFlavors = Lists.newArrayList();
        Collection<Variant> variants = Lists.newArrayList();
        Collection<String> variantNames = Lists.newArrayList();

        for (BuildTypeData<BuildType> btData : variantInputs.getBuildTypes().values()) {
            String buildTypeName = btData.getBuildType().getName();
            buildTypes.add(
                    BuildTypeContainerImpl.create(
                            btData,
                            variantDimensionInfo.buildTypes.contains(
                                    buildTypeName) /*includeProdSourceSet*/,
                            androidTests.buildTypes.contains(buildTypeName) /*includeAndroidTest*/,
                            unitTests.buildTypes.contains(buildTypeName) /*includeUnitTest*/,
                            extraModelInfo.getExtraBuildTypeSourceProviders(buildTypeName)));
        }
        for (ProductFlavorData<ProductFlavor> pfData : variantInputs.getProductFlavors().values()) {
            ProductFlavor productFlavor = pfData.getProductFlavor();
            Pair<String, String> dimensionValue =
                    Pair.of(productFlavor.getDimension(), productFlavor.getName());
            productFlavors.add(
                    ProductFlavorContainerImpl.createProductFlavorContainer(
                            pfData,
                            productFlavor,
                            variantDimensionInfo.flavors.contains(
                                    dimensionValue) /*includeProdSourceSet*/,
                            androidTests.flavors.contains(dimensionValue) /*includeAndroidTest*/,
                            unitTests.flavors.contains(dimensionValue) /*includeUnitTest*/,
                            extraModelInfo.getExtraFlavorSourceProviders(productFlavor.getName())));
        }

        String defaultVariant = variantModel.getDefaultVariant();
        String namespace = null;
        String androidTestNamespace = null;
        for (ComponentCreationConfig variant : variantModel.getVariants()) {
            variantNames.add(variant.getName());
            if (shouldBuildVariant) {
                variants.add(createVariant(variant));
            }

            // search for the namespace value. We can take the first variant as it's shared across
            // them. For AndroidTest we take the first non-null variant as well.
            namespace = variant.getNamespace().get();
            if (variant instanceof HasAndroidTest) {
                // TODO(b/176931684) Use AndroidTest.namespace instead after we stop
                //  supporting using applicationId to namespace the test component R class.

                AndroidTestCreationConfig test = ((HasAndroidTest) variant).getAndroidTest();
                if (test != null) {
                    androidTestNamespace = test.getNamespaceForR().get();
                }
            }
        }

        if (namespace == null) {
            // this should only happen if we have no variants, which is unlikely.
            namespace = "";
        }

        // get groupId/artifactId for project
        String groupId = project.getGroup().toString();

        AndroidGradlePluginProjectFlagsImpl flags = getFlags();

        // Collect all non test variants minimum information.
        Collection<VariantBuildInformation> variantBuildOutputs =
                variantModel.getVariants().stream()
                        .map(this::createBuildInformation)
                        .collect(Collectors.toList());

        return new DefaultAndroidProject(
                project.getName(),
                groupId,
                namespace,
                androidTestNamespace,
                defaultConfig,
                flavorDimensionList,
                buildTypes,
                productFlavors,
                variants,
                variantNames,
                defaultVariant,
                extension.getCompileSdkVersion(),
                bootClasspath,
                frameworkSource,
                cloneSigningConfigs(extension.getSigningConfigs()),
                aaptOptions,
                artifactMetaDataList,
                ImmutableList.of(),
                extension.getCompileOptions(),
                lintOptions,
                Collections.emptyList(),
//                CustomLintCheckUtils.getLocalCustomLintChecksForModel(
//                        project, variantModel.getSyncIssueReporter()),
                project.getBuildDir(),
                extension.getResourcePrefix(),
                ImmutableList.of(),
                extension.getBuildToolsVersion(),
                extension.getNdkVersion(),
                variantModel.getProjectTypeV1(),
                Version.BUILDER_MODEL_API_VERSION,
                isBaseSplit(),
                getDynamicFeatures(),
                viewBindingOptions,
                dependenciesInfo,
                flags,
                variantBuildOutputs);
    }

    private VariantBuildInformation createBuildInformation(ComponentCreationConfig component) {
        return new VariantBuildInformationImp(
                component.getName(),
                component.getTaskContainer().assembleTask.getName(),
                toAbsolutePath(
                        component
                                .getArtifacts()
                                .get(InternalArtifactType.APK_IDE_REDIRECT_FILE.INSTANCE)
                                .getOrNull()),
                component.getTaskContainer().getBundleTask() == null
                        ? component.computeTaskName("bundle")
                        : component.getTaskContainer().getBundleTask().getName(),
                toAbsolutePath(
                        component
                                .getArtifacts()
                                .get(InternalArtifactType.BUNDLE_IDE_REDIRECT_FILE.INSTANCE)
                                .getOrNull()),
                AnchorTaskNames.INSTANCE.getExtractApksAnchorTaskName(component),
                toAbsolutePath(
                        component
                                .getArtifacts()
                                .get(
                                        InternalArtifactType.APK_FROM_BUNDLE_IDE_REDIRECT_FILE
                                                .INSTANCE)
                                .getOrNull()));
    }

    private AndroidGradlePluginProjectFlagsImpl getFlags() {
        ImmutableMap.Builder<AndroidGradlePluginProjectFlags.BooleanFlag, Boolean> flags =
                ImmutableMap.builder();
        ProjectOptions projectOptions = variantModel.getProjectOptions();
        boolean finalResIds = !projectOptions.get(BooleanOption.USE_NON_FINAL_RES_IDS);
        flags.put(
                AndroidGradlePluginProjectFlags.BooleanFlag.APPLICATION_R_CLASS_CONSTANT_IDS,
                finalResIds);
        flags.put(
                AndroidGradlePluginProjectFlags.BooleanFlag.TEST_R_CLASS_CONSTANT_IDS, finalResIds);

        flags.put(
                AndroidGradlePluginProjectFlags.BooleanFlag.JETPACK_COMPOSE,
                variantModel.getVariants().stream()
                        .anyMatch(
                                variantProperties ->
                                        variantProperties.getBuildFeatures().getCompose()));

        flags.put(
                AndroidGradlePluginProjectFlags.BooleanFlag.ML_MODEL_BINDING,
                variantModel.getVariants().stream()
                        .anyMatch(
                                variantProperties ->
                                        variantProperties.getBuildFeatures().getMlModelBinding()));

        flags.put(
                AndroidGradlePluginProjectFlags.BooleanFlag.UNIFIED_TEST_PLATFORM,
                projectOptions.get(BooleanOption.ANDROID_TEST_USES_UNIFIED_TEST_PLATFORM));

        boolean transitiveRClass = !projectOptions.get(BooleanOption.NON_TRANSITIVE_R_CLASS);
        flags.put(AndroidGradlePluginProjectFlags.BooleanFlag.TRANSITIVE_R_CLASS, transitiveRClass);

        return new AndroidGradlePluginProjectFlagsImpl(flags.build());
    }

    protected boolean isBaseSplit() {
        return false;
    }

    @Nullable
    private static String toAbsolutePath(@Nullable RegularFile regularFile) {
        return regularFile != null ? regularFile.getAsFile().getAbsolutePath() : null;
    }

    protected boolean inspectManifestForInstantTag(@NonNull ComponentCreationConfig component) {
        int projectType = variantModel.getProjectTypeV1();
        if (projectType != PROJECT_TYPE_APP && projectType != PROJECT_TYPE_DYNAMIC_FEATURE) {
            return false;
        }

        VariantSources variantSources = component.getVariantSources();

        List<File> manifests = new ArrayList<>(variantSources.getManifestOverlays());
        File mainManifest = variantSources.getMainManifestIfExists();
        if (mainManifest != null) {
            manifests.add(mainManifest);
        }
        if (manifests.isEmpty()) {
            return false;
        }

        for (File manifest : manifests) {
            try (FileInputStream inputStream = new FileInputStream(manifest)) {
                XMLInputFactory factory = XMLInputFactory.newInstance();
                XMLEventReader eventReader = factory.createXMLEventReader(inputStream);

                while (eventReader.hasNext() && !eventReader.peek().isEndDocument()) {
                    XMLEvent event = eventReader.nextTag();
                    if (event.isStartElement()) {
                        StartElement startElement = event.asStartElement();
                        if (startElement.getName().getNamespaceURI().equals(DIST_URI)
                                && startElement
                                        .getName()
                                        .getLocalPart()
                                        .equalsIgnoreCase("module")) {
                            Attribute instant =
                                    startElement.getAttributeByName(new QName(DIST_URI, "instant"));
                            if (instant != null
                                    && (instant.getValue().equals(SdkConstants.VALUE_TRUE)
                                            || instant.getValue().equals(SdkConstants.VALUE_1))) {
                                eventReader.close();
                                return true;
                            }
                        }
                    } else if (event.isEndElement()
                            && ((EndElement) event)
                                    .getName()
                                    .getLocalPart()
                                    .equalsIgnoreCase("manifest")) {
                        break;
                    }
                }
                eventReader.close();
            } catch (XMLStreamException | IOException e) {
                variantModel
                        .getSyncIssueReporter()
                        .reportError(
                                Type.GENERIC,
                                "Failed to parse XML in "
                                        + manifest.getPath()
                                        + "\n"
                                        + e.getMessage());
            }
        }
        return false;
    }

    @NonNull
    protected Collection<String> getDynamicFeatures() {
        return ImmutableList.of();
    }

    @NonNull
    private VariantImpl buildVariant(
            @NonNull Project project,
            @Nullable String variantName,
            boolean shouldScheduleSourceGeneration) {
        if (variantName == null) {
            throw new IllegalArgumentException("Variant name cannot be null.");
        }
        for (ComponentCreationConfig component : variantModel.getVariants()) {
            if (component.getName().equals(variantName)) {
                VariantImpl variant = createVariant(component);
                if (shouldScheduleSourceGeneration) {
                    scheduleSourceGeneration(project, variant);
                }
                return variant;
            }
        }
        throw new IllegalArgumentException(
                String.format("Variant with name '%s' doesn't exist.", variantName));
    }

    /**
     * Used when fetching Android model and generating sources in the same Gradle invocation.
     *
     * <p>As this method modify Gradle tasks, it has to be run before task graph is calculated,
     * which means using {@link org.gradle.tooling.BuildActionExecuter.Builder#projectsLoaded(
     * org.gradle.tooling.BuildAction, org.gradle.tooling.IntermediateResultHandler)} to register
     * the {@link org.gradle.tooling.BuildAction}.
     */
    private static void scheduleSourceGeneration(
            @NonNull Project project, @NonNull Variant variant) {
        List<BaseArtifact> artifacts = Lists.newArrayList(variant.getMainArtifact());
        artifacts.addAll(variant.getExtraAndroidArtifacts());
        artifacts.addAll(variant.getExtraJavaArtifacts());

        Set<String> sourceGenerationTasks =
                artifacts
                        .stream()
                        .map(BaseArtifact::getIdeSetupTaskNames)
                        .flatMap(Collection::stream)
                        .map(taskName -> project.getPath() + ":" + taskName)
                        .collect(Collectors.toSet());

        try {
            StartParameter startParameter = project.getGradle().getStartParameter();
            Set<String> tasks = new HashSet<>(startParameter.getTaskNames());
            tasks.addAll(sourceGenerationTasks);
            startParameter.setTaskNames(tasks);
        } catch (Throwable e) {
            throw new RuntimeException("Can't modify scheduled tasks at current build step", e);
        }
    }

    @NonNull
    private VariantImpl createVariant(@NonNull ComponentCreationConfig component) {
        AndroidArtifact mainArtifact = createAndroidArtifact(ARTIFACT_MAIN, component);

        File manifest = component.getVariantSources().getMainManifestIfExists();
        if (manifest != null) {
            ManifestAttributeSupplier attributeSupplier =
                    new DefaultManifestParser(
                            manifest,
                            () -> true,
                            component.getComponentType().getRequiresManifest(),
                            variantModel.getSyncIssueReporter());
            try {
                validateMinSdkVersion(attributeSupplier);
                validateTargetSdkVersion(attributeSupplier);
            } catch (Throwable e) {
                variantModel
                        .getSyncIssueReporter()
                        .reportError(
                                Type.GENERIC,
                                "Failed to parse XML in "
                                        + manifest.getPath()
                                        + "\n"
                                        + e.getMessage());
            }
        }

        String variantName = component.getName();

        List<AndroidArtifact> extraAndroidArtifacts = Lists.newArrayList(
                extraModelInfo.getExtraAndroidArtifacts(variantName));
        LibraryDependencyCacheBuildService libraryDependencyCache =
                BuildServicesKt.getBuildService(
                                component.getServices().getBuildServiceRegistry(),
                                LibraryDependencyCacheBuildService.class)
                        .get();
        // Make sure all extra artifacts are serializable.
        List<JavaArtifact> clonedExtraJavaArtifacts =
                extraModelInfo.getExtraJavaArtifacts(variantName).stream()
                        .map(
                                javaArtifact ->
                                        JavaArtifactImpl.clone(
                                                javaArtifact,
                                                modelLevel,
                                                modelWithFullDependency,
                                                libraryDependencyCache))
                        .collect(Collectors.toList());

        if (component instanceof VariantCreationConfig) {
            VariantCreationConfig variant = (VariantCreationConfig) component;

            for (ComponentType componentType : ComponentType.Companion.getTestComponents()) {
//                ComponentCreationConfig testVariant =
//                        variant.getTestComponents().get(componentType);
//                if (testVariant != null) {
//                    switch ((ComponentTypeImpl) componentType) {
//                        case ANDROID_TEST:
//                            extraAndroidArtifacts.add(
//                                    createAndroidArtifact(
//                                            componentType.getArtifactName(), testVariant));
//                            break;
//                        case UNIT_TEST:
//                            clonedExtraJavaArtifacts.add(
//                                    createUnitTestsJavaArtifact(componentType, testVariant));
//                            break;
//                        default:
//                            throw new IllegalArgumentException(
//                                    String.format(
//                                            "Unsupported test variant type %s.", componentType));
//                    }
//                }
            }
        }

        // used for test only modules
        Collection<TestedTargetVariant> testTargetVariants = getTestTargetVariants(component);

        if (component instanceof VariantCreationConfig) {
            checkProguardFiles((VariantCreationConfig) component);
        }

        return new VariantImpl(
                variantName,
                component.getBaseName(),
                component.getBuildType(),
                getProductFlavorNames(component),
                new ProductFlavorImpl(
                        component.getModelV1LegacySupport().getMergedFlavor(),
                        component.getModelV1LegacySupport().getDslApplicationId()),
                mainArtifact,
                extraAndroidArtifacts,
                clonedExtraJavaArtifacts,
                testTargetVariants,
                inspectManifestForInstantTag(component),
                ImmutableList.of());
    }

    private void checkProguardFiles(@NonNull VariantCreationConfig component) {
        // We check for default files unless it's a base module, which can include default files.
        boolean isBaseModule = component.getComponentType().isBaseModule();
        boolean isDynamicFeature = component.getComponentType().isDynamicFeature();

        if (!isBaseModule) {
            List<File> consumerProguardFiles = component.getConsumerProguardFiles();

            ExportConsumerProguardFilesTask.checkProguardFiles(
                    project.getLayout().getBuildDirectory(),
                    isDynamicFeature,
                    consumerProguardFiles,
                    errorMessage ->
                            variantModel
                                    .getSyncIssueReporter()
                                    .reportError(Type.GENERIC, errorMessage));
        }
    }

    @NonNull
    private Collection<TestedTargetVariant> getTestTargetVariants(
            @NonNull ComponentCreationConfig component) {
//        if (extension instanceof TestAndroidConfig) {
//            TestAndroidConfig testConfig = (TestAndroidConfig) extension;
//
//            ArtifactCollection apkArtifacts =
//                    component
//                            .getVariantDependencies()
//                            .getArtifactCollection(
//                                    AndroidArtifacts.ConsumedConfigType.PROVIDED_CLASSPATH,
//                                    AndroidArtifacts.ArtifactScope.ALL,
//                                    AndroidArtifacts.ArtifactType.APK);
//
//            // while there should be single result, if the variant matching is broken, then
//            // we need to support this.
//            if (apkArtifacts.getArtifacts().size() == 1) {
//                ResolvedArtifactResult result =
//                        Iterables.getOnlyElement(apkArtifacts.getArtifacts());
//                String variant = LibraryUtils.getVariantName(result);
//
//                return ImmutableList.of(
//                        new TestedTargetVariantImpl(testConfig.getTargetProjectPath(), variant));
//            } else if (!apkArtifacts.getFailures().isEmpty()) {
//                // probably there was an error...
//                new DependencyFailureHandler()
//                        .addErrors(
//                                project.getPath() + "@" + component.getName() + "/testTarget",
//                                apkArtifacts.getFailures())
//                        .registerIssues(variantModel.getSyncIssueReporter());
//            }
//        }
        return ImmutableList.of();
    }

    private JavaArtifactImpl createUnitTestsJavaArtifact(
            @NonNull ComponentType componentType, @NonNull ComponentCreationConfig component) {
        ArtifactsImpl artifacts = component.getArtifacts();

        SourceProviders sourceProviders = determineSourceProviders(component);

        Pair<Dependencies, DependencyGraphs> result =
                getDependencies(component, buildMapping, modelLevel, modelWithFullDependency);

        Set<File> additionalTestClasses = new HashSet<>();
        additionalTestClasses.addAll(
                component
                        .getOldVariantApiLegacySupport()
                        .getVariantData()
                        .getAllPreJavacGeneratedBytecode()
                        .getFiles());
        additionalTestClasses.addAll(
                component
                        .getOldVariantApiLegacySupport()
                        .getVariantData()
                        .getAllPostJavacGeneratedBytecode()
                        .getFiles());
//        if (component.getGlobal().getTestOptions().getUnitTests().isIncludeAndroidResources()) {
//            additionalTestClasses.add(
//                    artifacts
//                            .get(InternalArtifactType.UNIT_TEST_CONFIG_DIRECTORY.INSTANCE)
//                            .get()
//                            .getAsFile());
//        }

        // The separately compile R class, if applicable.
        if (!extension.getAaptOptions().getNamespaced()
                && component.getAndroidResourcesCreationConfig() != null) {
            additionalTestClasses.add(
                    component
                            .getAndroidResourcesCreationConfig()
                            .getCompiledRClassArtifact()
                            .get()
                            .getAsFile());
        }

        // No files are possible if the SDK was not configured properly.
        File mockableJar =
                variantModel.getMockableJarArtifact().getFiles().stream().findFirst().orElse(null);

        return new JavaArtifactImpl(
                componentType.getArtifactName(),
                component.getTaskContainer().getAssembleTask().getName(),
                component.getTaskContainer().getCompileTask().getName(),
                Sets.newHashSet(TaskManager.CREATE_MOCKABLE_JAR_TASK_NAME),
                getGeneratedSourceFoldersForUnitTests(component),
                artifacts.get(JAVAC.INSTANCE).get().getAsFile(),
                additionalTestClasses,
                component
                        .getOldVariantApiLegacySupport()
                        .getVariantData()
                        .getJavaResourcesForUnitTesting(),
                mockableJar,
                result.getFirst(),
                result.getSecond(),
                sourceProviders.variantSourceProvider,
                sourceProviders.multiFlavorSourceProvider);
    }

    /** Gather the dependency graph for the specified <code>variantScope</code>. */
    @NonNull
    private Pair<Dependencies, DependencyGraphs> getDependencies(
            @NonNull ComponentCreationConfig component,
            @NonNull ImmutableMap<String, String> buildMapping,
            int modelLevel,
            boolean modelWithFullDependency) {
        Pair<Dependencies, DependencyGraphs> result;

        // If there is a missing flavor dimension then we don't even try to resolve dependencies
        // as it may fail due to improperly setup configuration attributes.
        if (variantModel.getSyncIssueReporter().hasIssue(Type.UNNAMED_FLAVOR_DIMENSION)) {
            result = Pair.of(DependenciesImpl.EMPTY, EmptyDependencyGraphs.EMPTY);
        } else {
            DependencyGraphBuilder graphBuilder = DependencyGraphBuilderKt.getDependencyGraphBuilder();
            // can't use ProjectOptions as this is likely to change from the initialization of
            // ProjectOptions due to how lint dynamically add/remove this property.

            if (modelLevel >= AndroidProject.MODEL_LEVEL_4_NEW_DEP_MODEL) {
                Level2DependencyModelBuilder modelBuilder =
                        new Level2DependencyModelBuilder(
                                component.getServices().getBuildServiceRegistry());
                ArtifactCollectionsInputs artifactCollectionsInputs =
                        new ArtifactCollectionsInputsImpl(
                                component,
                                ArtifactCollectionsInputs.RuntimeType.FULL,
                                buildMapping);
                graphBuilder.createDependencies(
                        modelBuilder,
                        artifactCollectionsInputs,
                        modelWithFullDependency,
                        variantModel.getSyncIssueReporter());
                result = Pair.of(DependenciesImpl.EMPTY, modelBuilder.createModel());
            } else {
                Level1DependencyModelBuilder modelBuilder =
                        new Level1DependencyModelBuilder(
                                component.getServices().getBuildServiceRegistry());
                ArtifactCollectionsInputs artifactCollectionsInputs =
                        new ArtifactCollectionsInputsImpl(
                                component,
                                ArtifactCollectionsInputs.RuntimeType.PARTIAL,
                                buildMapping);

                graphBuilder.createDependencies(
                        modelBuilder,
                        artifactCollectionsInputs,
                        modelWithFullDependency,
                        variantModel.getSyncIssueReporter());
                result = Pair.of(modelBuilder.createModel(), EmptyDependencyGraphs.EMPTY);
            }
        }

        return result;
    }

    private AndroidArtifact createAndroidArtifact(
            @NonNull String name, @NonNull ComponentCreationConfig component) {
        com.tyron.builder.api.variant.impl.SigningConfigImpl signingConfig = null;
        boolean isSigningReady = false;
        if (component instanceof ApkCreationConfig) {
            signingConfig = ((ApkCreationConfig) component).getSigningConfigImpl();
            if (signingConfig != null) {
                isSigningReady = signingConfig.hasConfig() && signingConfig.isSigningReady();
            }
        }
        String signingConfigName = null;
        if (signingConfig != null) {
            signingConfigName = signingConfig.getName();
        }

        SourceProviders sourceProviders = determineSourceProviders(component);

//        InstantRunImpl instantRun =
//                new InstantRunImpl(project.file("build_info_removed"), InstantRun.STATUS_REMOVED);

        Pair<Dependencies, DependencyGraphs> dependencies =
                getDependencies(component, buildMapping, modelLevel, modelWithFullDependency);

        Set<File> additionalClasses = new HashSet<>();
        additionalClasses.addAll(
                component
                        .getOldVariantApiLegacySupport()
                        .getVariantData()
                        .getAllPreJavacGeneratedBytecode()
                        .getFiles());
        additionalClasses.addAll(
                component
                        .getOldVariantApiLegacySupport()
                        .getVariantData()
                        .getAllPostJavacGeneratedBytecode()
                        .getFiles());
        if (component.getAndroidResourcesCreationConfig() != null) {
            additionalClasses.addAll(
                    component
                            .getAndroidResourcesCreationConfig()
                            .getCompiledRClasses(
                                    AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH)
                            .getFiles());
        }

        List<File> additionalRuntimeApks = new ArrayList<>();
//        TestOptionsImpl testOptions = null;
//
//        if (component.getComponentType().isTestComponent()
//                || component instanceof TestVariantImpl) {
//            Configuration testHelpers =
//                    project.getConfigurations()
//                            .findByName(SdkConstants.GRADLE_ANDROID_TEST_UTIL_CONFIGURATION);
//
//            // This may be the case with the experimental plugin.
//            if (testHelpers != null) {
//                additionalRuntimeApks.addAll(testHelpers.getFiles());
//            }
//
//            DeviceProviderInstrumentTestTask.checkForNonApks(
//                    additionalRuntimeApks,
//                    message ->
//                            variantModel.getSyncIssueReporter().reportError(Type.GENERIC, message));
//
//            TestOptions testOptionsDsl = extension.getTestOptions();
//            testOptions =
//                    new TestOptionsImpl(
//                            testOptionsDsl.getAnimationsDisabled(),
//                            testOptionsDsl.getExecutionEnum());
//        }

        MutableTaskContainer taskContainer = component.getTaskContainer();
        ArtifactsImpl artifacts = component.getArtifacts();
        final CodeShrinker codeShrinker;
        if (component instanceof ConsumableCreationConfig
                && ((ConsumableCreationConfig) component).getMinifiedEnabled()) {
            codeShrinker = CodeShrinker.R8;
        } else {
            codeShrinker = null;
        }

        InstantRun instantRun = new InstantRunImpl(new File("instantRun.info"), 0);

        TestOptions testOptions = null;

        return new AndroidArtifactImpl(
                name,
                ProjectInfo.getBaseName(project) + "-" + component.getBaseName(),
                taskContainer.getAssembleTask().getName(),
                artifacts.get(InternalArtifactType.APK_IDE_REDIRECT_FILE.INSTANCE).getOrNull(),
                isSigningReady
                        || component
                                .getOldVariantApiLegacySupport()
                                .getVariantData()
                                .outputsAreSigned,
                signingConfigName,
                taskContainer.getSourceGenTask().getName(),
                taskContainer.getCompileTask().getName(),
                getGeneratedSourceFolders(component),
                getGeneratedResourceFolders(component),
                artifacts.get(JAVAC.INSTANCE).get().getAsFile(),
                additionalClasses,
                component
                        .getOldVariantApiLegacySupport()
                        .getVariantData()
                        .getJavaResourcesForUnitTesting(),
                dependencies.getFirst(),
                dependencies.getSecond(),
                additionalRuntimeApks,
                sourceProviders.variantSourceProvider,
                sourceProviders.multiFlavorSourceProvider,
                component.getSupportedAbis(),
                instantRun,
                testOptions,
                null,
//                taskContainer.getConnectedTestTask() == null
//                        ? null
//                        : taskContainer.getConnectedTestTask().getName(),
                taskContainer.getBundleTask() == null
                        ? component.computeTaskName("bundle")
                        : taskContainer.getBundleTask().getName(),
                artifacts.get(InternalArtifactType.BUNDLE_IDE_REDIRECT_FILE.INSTANCE).getOrNull(),
                AnchorTaskNames.INSTANCE.getExtractApksAnchorTaskName(component),
                artifacts
                        .get(InternalArtifactType.APK_FROM_BUNDLE_IDE_REDIRECT_FILE.INSTANCE)
                        .getOrNull(),
                codeShrinker);
    }

    private void validateMinSdkVersion(@NonNull ManifestAttributeSupplier supplier) {
        if (supplier.getMinSdkVersion() != null) {
            // report an error since min sdk version should not be in the manifest.
            variantModel
                    .getSyncIssueReporter()
                    .reportError(
                            IssueReporter.Type.MIN_SDK_VERSION_IN_MANIFEST,
                            "The minSdk version should not be declared in the android"
                                    + " manifest file. You can move the version from the manifest"
                                    + " to the defaultConfig in the build.gradle file.");
        }
    }

    private void validateTargetSdkVersion(@NonNull ManifestAttributeSupplier supplier) {
        if (supplier.getTargetSdkVersion() != null) {
            // report a warning since target sdk version should not be in the manifest.
            variantModel
                    .getSyncIssueReporter()
                    .reportWarning(
                            IssueReporter.Type.TARGET_SDK_VERSION_IN_MANIFEST,
                            "The targetSdk version should not be declared in the android"
                                    + " manifest file. You can move the version from the manifest"
                                    + " to the defaultConfig in the build.gradle file.");
        }
    }

    private static SourceProviders determineSourceProviders(
            @NonNull ComponentCreationConfig component) {
        SourceProvider variantSourceProvider =
                component.getVariantSources().getVariantSourceProvider();
        SourceProvider multiFlavorSourceProvider =
                component.getVariantSources().getMultiFlavorSourceProvider();

        return new SourceProviders(
                variantSourceProvider != null
                        ? new SourceProviderImpl(variantSourceProvider, component.getSources())
                        : null,
                multiFlavorSourceProvider != null
                        ? new SourceProviderImpl(multiFlavorSourceProvider)
                        : null);
    }

    @NonNull
    private static List<String> getProductFlavorNames(@NonNull ComponentCreationConfig component) {
        return component.getProductFlavorList().stream()
                .map(com.tyron.builder.gradle.internal.core.ProductFlavor::getName)
                .collect(Collectors.toList());
    }

    @NonNull
    public static List<File> getGeneratedSourceFoldersForUnitTests(
            @NonNull ComponentCreationConfig component) {
        return Streams.stream(getGeneratedSourceFoldersFileCollectionForUnitTests(component))
                .collect(Collectors.toList());
    }

    @NonNull
    private static FileCollection getGeneratedSourceFoldersFileCollectionForUnitTests(
            @NonNull ComponentCreationConfig component) {
        ConfigurableFileCollection fileCollection = component.getServices().fileCollection();
        fileCollection.from(
                component
                        .getSources()
                        .getJava()
                        .variantSourcesForModel(
                                directoryEntry ->
                                        directoryEntry.isGenerated()
                                                && directoryEntry.getShouldBeAddedToIdeModel()));
        if (component.getOldVariantApiLegacySupport() != null) {
            fileCollection.from(
                    component
                            .getOldVariantApiLegacySupport()
                            .getVariantData()
                            .getExtraGeneratedSourceFoldersOnlyInModel());
        }
        fileCollection.from(
                component.getArtifacts().get(InternalArtifactType.AP_GENERATED_SOURCES.INSTANCE));
        fileCollection.disallowChanges();
        return fileCollection;
    }

    @NonNull
    public static List<File> getGeneratedSourceFolders(@NonNull ComponentCreationConfig component) {
        return Streams.stream(getGeneratedSourceFoldersFileCollection(component))
                .collect(Collectors.toList());
    }

    @NonNull
    public static FileCollection getGeneratedSourceFoldersFileCollection(
            @NonNull ComponentCreationConfig component) {
        ConfigurableFileCollection fileCollection = component.getServices().fileCollection();
        ArtifactsImpl artifacts = component.getArtifacts();
        fileCollection.from(getGeneratedSourceFoldersFileCollectionForUnitTests(component));
        Callable<Directory> aidlCallable =
                () -> artifacts.get(AIDL_SOURCE_OUTPUT_DIR.INSTANCE).getOrNull();
        fileCollection.from(aidlCallable);
        if (component.getBuildConfigCreationConfig() != null
                && component.getBuildConfigCreationConfig().getBuildConfigType()
                        == BuildConfigType.JAVA_SOURCE) {
            Callable<Directory> buildConfigCallable =
                    () -> component.getPaths().getBuildConfigSourceOutputDir().getOrNull();
            fileCollection.from(buildConfigCallable);
        }
        // this is incorrect as it cannot get the final value, we should always add the folder
        // as a potential source origin and let the IDE deal with it.
        boolean ndkMode = false;
        VariantCreationConfig mainVariant;
        if (component instanceof NestedComponentCreationConfig) {
            mainVariant = ((NestedComponentCreationConfig) component).getMainVariant();
        } else {
            mainVariant = (VariantCreationConfig) component;
        }
        if (mainVariant.getRenderscriptCreationConfig() != null) {
            ndkMode =
                    mainVariant.getRenderscriptCreationConfig().getDslRenderscriptNdkModeEnabled();
        }
        if (!ndkMode) {
            Callable<Directory> renderscriptCallable =
                    () -> artifacts.get(RENDERSCRIPT_SOURCE_OUTPUT_DIR.INSTANCE).getOrNull();
            fileCollection.from(renderscriptCallable);
        }
        boolean isDataBindingEnabled = component.getBuildFeatures().getDataBinding();
        boolean isViewBindingEnabled = component.getBuildFeatures().getViewBinding();
        if (isDataBindingEnabled || isViewBindingEnabled) {
            Callable<Directory> dataBindingCallable =
                    () -> artifacts.get(DATA_BINDING_BASE_CLASS_SOURCE_OUT.INSTANCE).getOrNull();
            fileCollection.from(dataBindingCallable);
        }
        fileCollection.disallowChanges();
        return fileCollection;
    }

    @NonNull
    public static List<File> getGeneratedResourceFolders(
            @NonNull ComponentCreationConfig component) {
        return Streams.stream(getGeneratedResourceFoldersFileCollection(component))
                .collect(Collectors.toList());
    }

    @NonNull
    public static FileCollection getGeneratedResourceFoldersFileCollection(
            @NonNull ComponentCreationConfig component) {
        ConfigurableFileCollection fileCollection = component.getServices().fileCollection();
        if (component.getOldVariantApiLegacySupport() != null) {
            fileCollection.from(
                    component
                            .getOldVariantApiLegacySupport()
                            .getVariantData()
                            .getExtraGeneratedResFolders());
        }
        if (component.getBuildFeatures().getRenderScript()) {
            fileCollection.from(component.getArtifacts()
                    .get(InternalArtifactType.RENDERSCRIPT_GENERATED_RES.INSTANCE));
        }
        if (component.getBuildFeatures().getAndroidResources()) {
            if (component
                    .getArtifacts()
                    .get(InternalArtifactType.GENERATED_RES.INSTANCE)
                    .isPresent()) {
                fileCollection.from(
                        component.getArtifacts().get(InternalArtifactType.GENERATED_RES.INSTANCE));
            }
        }
        fileCollection.disallowChanges();
        return fileCollection;
    }

    @NonNull
    private static Collection<SigningConfig> cloneSigningConfigs(
            @NonNull Collection<? extends SigningConfig> signingConfigs) {
        return signingConfigs.stream()
                .map((Function<SigningConfig, SigningConfig>)
                        SigningConfigImpl::createSigningConfig)
                .collect(Collectors.toList());
    }

    private static class SourceProviders {
        protected SourceProviderImpl variantSourceProvider;
        protected SourceProviderImpl multiFlavorSourceProvider;

        public SourceProviders(
                SourceProviderImpl variantSourceProvider,
                SourceProviderImpl multiFlavorSourceProvider) {
            this.variantSourceProvider = variantSourceProvider;
            this.multiFlavorSourceProvider = multiFlavorSourceProvider;
        }
    }

    private void initBuildMapping(@NonNull Project project) {
        if (buildMapping == null) {
            buildMapping = BuildMappingUtils.computeBuildMapping(project.getGradle());
        }
    }
}
