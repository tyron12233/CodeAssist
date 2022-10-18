package com.tyron.builder.gradle

import com.tyron.builder.api.transform.Transform
import com.tyron.builder.api.variant.VariantFilter
import com.tyron.builder.gradle.api.AndroidSourceSet
import com.tyron.builder.gradle.api.BaseVariantOutput
import com.tyron.builder.gradle.internal.CompileOptions
import com.tyron.builder.gradle.internal.coverage.JacocoOptions
import com.tyron.builder.gradle.internal.dsl.AaptOptions
import com.tyron.builder.gradle.internal.dsl.AdbOptions
import com.tyron.builder.gradle.internal.dsl.CoreBuildType
import com.tyron.builder.gradle.internal.dsl.CoreProductFlavor
import com.tyron.builder.gradle.internal.dsl.DexOptions
import com.tyron.builder.gradle.internal.dsl.PackagingOptions
import com.tyron.builder.gradle.internal.dsl.Splits
import com.tyron.builder.core.LibraryRequest
import com.tyron.builder.model.DataBindingOptions
import com.tyron.builder.model.SigningConfig
import org.gradle.api.Action
import org.gradle.api.Incubating
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.Internal
import java.io.File

/**
 * User configuration settings for all android plugins.
 *
 *
 * DO NOT ADD ANYTHING THERE.
 *
 */
@Suppress("DEPRECATION")
@Deprecated("Use {@link BaseExtension} instead.")
interface AndroidConfig {

    /**
     * Specifies the version of the
     * [SDK Build Tools](https://developer.android.com/studio/releases/build-tools.html)
     * to use when building your project.
     *
     * When using Android plugin 3.0.0 or later, configuring this property is optional. By
     * default, the plugin uses the minimum version of the build tools required by the
     * [version of the plugin](https://developer.android.com/studio/releases/gradle-plugin.html#revisions)
     * you're using.
     * To specify a different version of the build tools for the plugin to use,
     * specify the version as follows:
     *
     * ```
     * android {
     *     // Specifying this property is optional.
     *     buildToolsVersion "26.0.0"
     * }
     * ```
     *
     * For a list of build tools releases, read
     * [the release notes](https://developer.android.com/studio/releases/build-tools.html#notes).
     *
     * Note that the value assigned to this property is parsed and stored in a normalized form,
     * so reading it back may give a slightly different result.
     */
    val buildToolsVersion: String

    /**
     * Specifies the API level to compile your project against. The Android plugin requires you to
     * configure this property.
     *
     * This means your code can use only the Android APIs included in that API level and lower.
     * You can configure the compile sdk version by adding the following to the `android`
     * block: `compileSdkVersion 26`.
     *
     * You should generally
     * [use the most up-to-date API level](https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#ApiLevels)
     * available.
     * If you are planning to also support older API levels, it's good practice to
     * [use the Lint tool](https://developer.android.com/studio/write/lint.html)
     * to check if you are using APIs that are not available in earlier API levels.
     *
     * The value you assign to this property is parsed and stored in a normalized form, so
     * reading it back may return a slightly different value.
     */
    val compileSdkVersion: String?

//    /**
//     * This property is for internal use only.
//     *
//     * To specify the version of the
//     * [SDK Build Tools](https://developer.android.com/studio/releases/build-tools.html)
//     * that the Android plugin should use, use
//     * [buildToolsVersion](com.tyron.builder.gradle.BaseExtension.html#com.tyron.builder.gradle.BaseExtension:buildToolsVersion)
//     * instead.
//     */
//    @get:Internal
//    val buildToolsRevision: Revision

    /**
     * Specifies the version of the module to publish externally. This property is generally useful
     * only to library modules that you intend to publish to a remote repository, such as Maven.
     *
     * If you don't configure this property, the Android plugin publishes the release version of
     * the module by default. If the module configures
     * [product flavors](https://developer.android.com/studio/build/build-variants.html#product-flavors),
     * you need to configure this property with the name of the variant you want the
     * plugin to publish, as shown below:
     *
     * ```
     * android {
     *     // Specifies the 'demoDebug' build variant as the default variant
     *     // that the plugin should publish to external consumers.
     *     defaultPublishConfig 'demoDebug'
     * }
     * ```
     *
     * If you plan to only consume your library module locally, you do not need to configure this
     * property. Android plugin 3.0.0 and higher use
     * [variant-aware dependency resolution](https://developer.android.com/studio/build/gradle-plugin-3-0-0-migration.html#variant_aware)
     * to automatically match the variant of the producer to that of the
     * consumer. That is, when publishing a module to another local module, the plugin no longer
     * respects this property when determining which version of the module to publish to the
     * consumer.
     */
    val defaultPublishConfig: String

    /**
     * Specifies variants the Android plugin should include or remove from your Gradle project.
     *
     * By default, the Android plugin creates a build variant for every possible combination of
     * the product flavors and build types that you configure, and adds them to your Gradle project.
     * However, there may be certain build variants that either you do not need or do not make sense
     * in the context of your project. You can remove certain build variant configurations by
     * [creating  a variant filter](https://developer.android.com/studio/build/build-variants.html#filter-variants)
     * in your sub-project-level `build.gradle` file.
     *
     * The following example tells the plugin to ignore all variants that combine the "dev"
     * product flavor and the "release" build type:
     *
     * ```
     * android {
     *     ...
     *     variantFilter { variant ->
     *
     *         def buildTypeName = variant.buildType*.name
     *         def flavorName = variant.flavors*.name
     *
     *         if (flavorName.contains("dev") && buildTypeName.contains("release")) {
     *             // Tells Gradle to ignore each variant that satisfies the conditions above.
     *             setIgnore(true)
     *         }
     *     }
     * }
     * ```
     *
     * During subsequent builds, Gradle ignores any build variants that meet the conditions you
     * specify.
     * If you're using [Android Studio](https://developer.android.com/studio/index.html),
     * those variants no longer appear in the drop down menu when you click
     * **Build &gt; Select Build Variant** from the menu bar.
     *
     * @see com.tyron.builder.gradle.internal.api.VariantFilter
     */
    val variantFilter: Action<VariantFilter>?

    /**
     * Specifies APK install options for the [Android Debug Bridge
     * (ADB)](https://developer.android.com/studio/command-line/adb.html).
     *
     * @see com.tyron.builder.gradle.internal.dsl.AdbOptions
     */
    val adbOptions: AdbOptions

    /**
     * Specifies this project's resource prefix to Android Studio for editor features, such as Lint
     * checks. This property is useful only when using Android Studio.
     *
     * Including unique prefixes for project resources helps avoid naming collisions with
     * resources from other projects.
     *
     * For example, when creating a library with String resources,
     * you may want to name each resource with a unique prefix, such as "`mylib_`"
     * to avoid naming collisions with similar resources that the consumer defines.
     *
     * You can then specify this prefix, as shown below, so that Android Studio expects this prefix
     * when you name project resources:
     *
     * <pre>
     * // This property is useful only when developing your project in Android Studio.
     * resourcePrefix 'mylib_'
    </pre> *
     */
    val resourcePrefix: String?

    /**
     * Specifies the names of product flavor dimensions for this project.
     *
     * To configure flavor dimensions, use
     * [`flavorDimensions`](com.tyron.builder.gradle.BaseExtension.html#com.tyron.builder.gradle.BaseExtension:flavorDimensions(java.lang.String[])).
     *
     * To learn more, read
     * [combine multiple product flavors](https://developer.android.com/studio/build/build-variants.html#flavor-dimensions).
     */
    val flavorDimensionList: List<String>

    /**
     * Specifies whether to build APK splits or multiple APKs from configurations in the [ ] block.
     *
     *
     * When you set this property to `true`, the Android plugin generates each object
     * in the [splits][com.tyron.builder.gradle.internal.dsl.Splits] block as a portion of a
     * whole APK, called an *APK split*. Compared to building multiple APKs, each APK split
     * includes only the components that each ABI or screen density requires. Generating APK splits
     * is an incubating feature, which requires you to set the
     * [min sdk version][com.tyron.builder.gradle.internal.dsl.BaseFlavor.minSdkVersion] to `21` or
     * higher, and is currently supported only when publishing
     * [Android Instant Apps](https://d.android.com/instant-apps).
     *
     * When you do not configure this property or set it to `false` (default), the
     * Android plugin builds separate APKs for each object you configure in the [Splits]
     * block that you can deploy to a device.
     * To learn more about building different versions of your app that each target a different
     * [Application Binary Interfaces](https://developer.android.com/ndk/guides/abis.html)
     * or screen density, read
     * [Build Multiple APKs](https://developer.android.com/studio/build/configure-apk-splits.html).
     */
    @get:Incubating
    val generatePureSplits: Boolean

    /**
     * Specifies defaults for variant properties that the Android plugin applies to all build
     * variants.
     *
     * You can override any `defaultConfig` property when
     * [configuring product flavors](https://developer.android.com/studio/build/build-variants.html#product-flavors).
     *
     * @see com.tyron.builder.gradle.internal.dsl.ProductFlavor
     */
    val defaultConfig: CoreProductFlavor

    /**
     * Specifies options for the Android Asset Packaging Tool (AAPT).
     *
     * @see com.tyron.builder.gradle.internal.dsl.AaptOptions
     */
    val aaptOptions: AaptOptions

    /**
     * Specifies Java compiler options, such as the language level of the Java source code and
     * generated bytecode.
     *
     * @see com.tyron.builder.gradle.internal.CompileOptions
     */
    val compileOptions: CompileOptions

    /**
     * Specifies options for the DEX tool, such as enabling library pre-dexing.
     *
     * Experimenting with DEX options tailored for your workstation may improve build
     * performance. To learn more, read
     * [Optimize your build](https://developer.android.com/studio/build/optimize-your-build.html#dex_options).
     *
     * @see com.tyron.builder.gradle.internal.dsl.DexOptions
     */
    val dexOptions: DexOptions

    /**
     * Configure JaCoCo version that is used for offline instrumentation and coverage report.
     *
     * To specify the version of JaCoCo you want to use, add the following to `build.gradle` file:
     * ```
     * android {
     *     jacoco {
     *         version "&lt;jacoco-version&gt;"
     *     }
     * }
     * ```
     */
    val jacoco: JacocoOptions

//    /**
//     * Specifies options for the lint tool.
//     *
//     * Android Studio and the Android SDK provide a code scanning tool called lint that can help
//     * you to identify and correct problems with the structural quality of your code without having
//     * to execute the app or write test cases. Each problem the tool detects is reported with a
//     * description message and a severity level, so that you can quickly prioritize the critical
//     * improvements that need to be made.
//     *
//     *
//     * This property allows you to configure certain lint options, such as which checks to run or
//     * ignore. If you're using Android Studio, you can
//     * [configure similar lint options](https://developer.android.com/studio/write/lint.html#cis)
//     * from the IDE.
//     *
//     * To learn more about using and running lint, read
//     * [Improve Your Code with Lint](https://developer.android.com/studio/write/lint.html).
//     *
//     * @see com.tyron.builder.gradle.internal.dsl.LintOptions
//     */
//    val lintOptions: LintOptions
//
//    /** Replaced by [com.tyron.builder.api.dsl.CommonExtension.externalNativeBuild] */
//    val externalNativeBuild: CoreExternalNativeBuild

    /**
     * Specifies options and rules that determine which files the Android plugin packages into your
     * APK.
     *
     * For example, the following example tells the plugin to avoid packaging files that are
     * intended only for testing:
     *
     * ```
     * packagingOptions {
     *     // Tells the plugin to not include any files in the 'testing-data/' directory,
     *     // which is specified as an absolute path from the root of the APK archive.
     *     // The exclude property includes certain defaults paths to help you avoid common
     *     // duplicate file errors when building projects with multiple dependencies.
     *     exclude "/testing-data/**"
     * }
     * ```
     *
     * To learn more about how to specify rules for packaging, merging, and excluding files, see
     * [PackagingOptions]
     *
     * @see com.tyron.builder.gradle.internal.dsl.PackagingOptions
    */// */
    val packagingOptions: PackagingOptions

    /**
     * Specifies configurations for
     * [building multiple APKs](https://developer.android.com/studio/build/configure-apk-splits.html) or APK splits.
     *
     * To generate APK splits, you need to also set
     * [`generatePureSplits`](com.tyron.builder.gradle.BaseExtension.html#com.tyron.builder.gradle.BaseExtension:generatePureSplits)
     * to `true`. However, generating APK splits is an incubating feature, which requires you to set
     * [minSdkVersion][com.tyron.builder.gradle.internal.dsl.BaseFlavor.minSdkVersion]
     * to `21` or higher, and is currently supported only when publishing
     * [Android Instant Apps](https://d.android.com/instant-apps).
     *
     * @see com.tyron.builder.gradle.internal.dsl.Splits
     */
    val splits: Splits

//    /**
//     * Specifies options for how the Android plugin should run local and instrumented tests.
//     *
//     *
//     * To learn more, read
//     * [Configure Gradle test options](https://developer.android.com/studio/test/index.html#test_options).
//     *
//     * @see com.tyron.builder.gradle.internal.dsl.TestOptions
//     */
//    val testOptions: TestOptions
//
//    /** List of device providers  */
//    val deviceProviders: List<DeviceProvider>
//
//    /** List of remote CI servers.  */
//    val testServers: List<TestServer>

    @Deprecated(
        "The transform API is planned to be removed in Android Gradle plugin 8.0."
    )
    val transforms: List<Transform>

    @Deprecated(
        "The transform API is planned to be removed in Android Gradle plugin 8.0."
    )
    val transformsDependencies: List<List<Any>>

    /** Replaced by [com.tyron.builder.api.dsl.CommonExtension.productFlavors] */
    val productFlavors: Collection<CoreProductFlavor>

    /** Replaced by [com.tyron.builder.api.dsl.CommonExtension.buildTypes] */
    val buildTypes: Collection<CoreBuildType>

    /** Replaced by [com.tyron.builder.api.dsl.CommonExtension.signingConfigs] */
    val signingConfigs: Collection<SigningConfig>

    /**
     * Encapsulates source set configurations for all variants.
     *
     *
     * The Android plugin looks for your project's source code and resources in groups of
     * directories called
     * *[source sets](https://developer.android.com/studio/build/index.html#sourcesets)*.
     * Each source set also determines the scope of build outputs that should consume its code and
     * resources. For example, when creating a new project from Android Studio, the IDE creates
     * directories for a `main/` source set that contains the code and resources you want
     * to share between all your build variants.
     *
     * You can then define basic functionality in the `main/` source set, but use
     * product flavor source sets to change only the branding of your app between different clients,
     * or include special permissions and logging functionality to only "debug" versions of your
     * app.
     *
     * The Android plugin expects you to organize files for source set directories a certain way,
     * similar to the `main/` source set. For example, Gradle expects Java class files
     * that are specific to your "debug" build type to be located in the `src/debug/java/`
     *  directory.
     *
     *
     * Gradle provides a useful task to shows you how to organize your files for each build
     * type-, product flavor-, and build variant-specific source set. you can run this task from the
     * command line as follows:
     *
     * `./gradlew sourceSets`
     *
     *
     * The following sample output describes where Gradle expects to find certain files for the
     * "debug" build type:
     *
     * ```
     * ------------------------------------------------------------
     * Project :app
     * ------------------------------------------------------------
     *
     * ...
     *
     * debug
     * ----
     * Compile configuration: compile
     * build.gradle name: android.sourceSets.debug
     * Java sources: [app/src/debug/java]
     * Manifest file: app/src/debug/AndroidManifest.xml
     * Android resources: [app/src/debug/res]
     * Assets: [app/src/debug/assets]
     * AIDL sources: [app/src/debug/aidl]
     * RenderScript sources: [app/src/debug/rs]
     * JNI sources: [app/src/debug/jni]
     * JNI libraries: [app/src/debug/jniLibs]
     * Java-style resources: [app/src/debug/resources]
     * ```
     *
     *
     * If you have sources that are not organized into the default source set directories that
     * Gradle expects, as described in the sample output above, you can use the `sourceSet`
     *  block to change where Gradle looks to gather files for each component of a given
     * source set. You don't need to relocate the files; you only need to provide Gradle with the
     * path(s), relative to the module-level `build.gradle` file, where Gradle should
     * expect to find files for each source set component.
     *
     *
     * **Note:** You should specify only static paths whenever possible. Specifying dynamic
     * paths reduces build speed and consistency.
     *
     *
     * The following code sample maps sources from the `app/other/` directory to
     * certain components of the `main` source set and changes the root directory of the
     * `androidTest` source set:
     *
     * ```
     * android {
     * ...
     *     sourceSets {
     *     // Encapsulates configurations for the main source set.
     *         main {
     *             // Changes the directory for Java sources. The default directory is
     *             // 'src/main/java'.
     *             java.srcDirs = ['other/java']
     *
     *             // If you list multiple directories, Gradle uses all of them to collect
     *             // sources. Because Gradle gives these directories equal priority, if
     *             // you define the same resource in more than one directory, you get an
     *             // error when merging resources. The default directory is 'src/main/res'.
     *             res.srcDirs = ['other/res1', 'other/res2']
     *
     *             // Note: You should avoid specifying a directory which is a parent to one
     *             // or more other directories you specify. For example, avoid the following:
     *             // res.srcDirs = ['other/res1', 'other/res1/layouts', 'other/res1/strings']
     *             // You should specify either only the root 'other/res1' directory, or only the
     *             // nested 'other/res1/layouts' and 'other/res1/strings' directories.
     *
     *             // For each source set, you can specify only one Android manifest.
     *             // By default, Android Studio creates a manifest for your main source
     *             // set in the src/main/ directory.
     *             manifest.srcFile 'other/AndroidManifest.xml'
     *             ...
     *         }
     *
     *         // Create additional blocks to configure other source sets.
     *         androidTest {
     *             // If all the files for a source set are located under a single root
     *             // directory, you can specify that directory using the setRoot property.
     *             // When gathering sources for the source set, Gradle looks only in locations
     *             // relative to the root directory you specify. For example, after applying the
     *             // configuration below for the androidTest source set, Gradle looks for Java
     *             // sources only in the src/tests/java/ directory.
     *             setRoot 'src/tests'
     *             ...
     *         }
     *     }
     * }
     * ```
     *
     * @see com.tyron.builder.gradle.internal.dsl.AndroidSourceSetFactory
     */
    val sourceSets: NamedDomainObjectContainer<AndroidSourceSet>

    /** build outputs for all variants  */
    val buildOutputs: Collection<BaseVariantOutput>

    @get:Suppress("WrongTerminology")
    @Deprecated("Use aidlPackagedList instead", ReplaceWith("aidlPackagedList"))
    val aidlPackageWhiteList: MutableCollection<String>?

    /** Aidl files to package in the aar. */
    val aidlPackagedList: MutableCollection<String>?

    val libraryRequests: MutableCollection<LibraryRequest>

    /**
     * Specifies options for the
     * [Data Binding Library](https://developer.android.com/topic/libraries/data-binding/index.html).
     *
     * Data binding helps you write declarative layouts and minimize the glue code necessary to
     * bind your application logic and layouts.
     */
    val dataBinding: DataBindingOptions

    /** Whether the feature module is the base feature.  */
    val baseFeature: Boolean?

    /**
     * Name of the build type that will be used when running Android (on-device) tests.
     *
     * Defaults to the tested build type.
     *
     * FIXME this should not be here, but it has to be because of gradle-core not knowing
     * anything besides this interface. This will be fixed with the new gradle-api based extension
     * interfaces.
     */
    val testBuildType: String?

    /**
     * Requires the specified NDK version to be used.
     *
     * <p>Use this to specify a fixed NDK version. Without this, each new version of the Android
     * Gradle Plugin will choose a specific version of NDK to use, so upgrading the plugin also
     * means upgrading the NDK. Locking to a specific version can increase repeatability of the
     * build.
     *
     * <pre>
     * android {
     *     // Use a fixed NDK version
     *     ndkVersion '20.1.5948944'
     * }
     * </pre>
     *
     * <p> The required format of the version is <code>major.minor.build</code>. It's not legal to
     * specify less precision.
     * <p> If <code>ndk.dir</code> is specified in <code>local.properties</code> file then the NDK
     * that it points to must match the <code>android.ndkVersion</code>.
     *
     * <p> Prior to Android Gradle Plugin version 3.5, the highest installed version of NDK will
     * be used.
     * <p> In Android Gradle Plugin 3.4, specifying <code>android.ndkVersion</code> was not an
     * error, but the value would be ignored.
     * <p> Prior to Android Gradle Plugin version 3.4, it was illegal to specify
     * <code>android.ndkVersion</code>.
     *
     * <p>For additional information about NDK installation see <a
     * href="https://developer.android.com/studio/projects/install-ndk">Install and configure
     * the NDK</a>.
     *
     * @param version the NDK to use.
     */
    val ndkVersion: String?

    /** Returns the list of files that form bootClasspath used for compilation.  */
    val bootClasspath: List<File>
}
