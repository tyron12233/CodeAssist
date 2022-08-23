package com.tyron.builder.plugin;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public class SdkConstants {

    /**
     * The encoding we strive to use for all files we write.
     * <p>
     * When possible, use the APIs which take a {@link java.nio.charset.Charset} and pass in
     * {@link com.google.common.base.Charsets#UTF_8} instead of using the String encoding
     * method.
     */
    public static final String UTF_8 = "UTF-8";                                       //$NON-NLS-1$
    /**
     * Charset for the ini file handled by the SDK.
     */
    public static final String INI_CHARSET = UTF_8;
    /**
     * Path separator used by Gradle
     */
    public static final String GRADLE_PATH_SEPARATOR = ":";                           //$NON-NLS-1$
    /**
     * An SDK Project's AndroidManifest.xml file
     */
    public static final String FN_ANDROID_MANIFEST_XML = "AndroidManifest.xml";        //$NON-NLS-1$
    /**
     * pre-dex jar filename. i.e. "classes.jar"
     */
    public static final String FN_CLASSES_JAR = "classes.jar";                        //$NON-NLS-1$
    /**
     * Dex filename inside the APK. i.e. "classes.dex"
     */
    public static final String FN_APK_CLASSES_DEX = "classes.dex";                    //$NON-NLS-1$
    /**
     * Dex filename inside the APK. i.e. "classes.dex"
     */
    public static final String FN_APK_CLASSES_N_DEX = "classes%d.dex";

    /**
     * An SDK Project's build.xml file
     */
    public static final String FN_BUILD_XML = "build.xml";                            //$NON-NLS-1$
    /**
     * An SDK Project's build.gradle file
     */
    public static final String FN_BUILD_GRADLE = "build.gradle";                      //$NON-NLS-1$
    /**
     * An SDK Project's settings.gradle file
     */
    public static final String FN_SETTINGS_GRADLE = "settings.gradle";                //$NON-NLS-1$
    /**
     * An SDK Project's gradle.properties file
     */
    public static final String FN_GRADLE_PROPERTIES = "gradle.properties";            //$NON-NLS-1$
    /**
     * An SDK Project's gradle daemon executable
     */
    public static final String FN_GRADLE_UNIX = "gradle";                             //$NON-NLS-1$
    /**
     * An SDK Project's gradle.bat daemon executable (gradle for windows)
     */
    public static final String FN_GRADLE_WIN = FN_GRADLE_UNIX + ".bat";               //$NON-NLS-1$
    /**
     * An SDK Project's gradlew file
     */
    public static final String FN_GRADLE_WRAPPER_UNIX = "gradlew";                    //$NON-NLS-1$
    /**
     * An SDK Project's gradlew.bat file (gradlew for windows)
     */
    public static final String FN_GRADLE_WRAPPER_WIN = FN_GRADLE_WRAPPER_UNIX + ".bat";
    //$NON-NLS-1$
    /**
     * An SDK Project's gradle wrapper library
     */
    public static final String FN_GRADLE_WRAPPER_JAR = "gradle-wrapper.jar";          //$NON-NLS-1$
    /**
     * Name of the framework library, i.e. "android.jar"
     */
    public static final String FN_FRAMEWORK_LIBRARY = "android.jar";                  //$NON-NLS-1$
    /**
     * Name of the framework library, i.e. "uiautomator.jar"
     */
    public static final String FN_UI_AUTOMATOR_LIBRARY = "uiautomator.jar";           //$NON-NLS-1$
    /**
     * Name of the layout attributes, i.e. "attrs.xml"
     */
    public static final String FN_ATTRS_XML = "attrs.xml";                            //$NON-NLS-1$
    /**
     * Name of the layout attributes, i.e. "attrs_manifest.xml"
     */
    public static final String FN_ATTRS_MANIFEST_XML = "attrs_manifest.xml";          //$NON-NLS-1$
    /**
     * framework aidl import file
     */
    public static final String FN_FRAMEWORK_AIDL = "framework.aidl";                  //$NON-NLS-1$
    /**
     * framework renderscript folder
     */
    public static final String FN_FRAMEWORK_RENDERSCRIPT = "renderscript";            //$NON-NLS-1$
    /**
     * framework include folder
     */
    public static final String FN_FRAMEWORK_INCLUDE = "include";                      //$NON-NLS-1$
    /**
     * framework include (clang) folder
     */
    public static final String FN_FRAMEWORK_INCLUDE_CLANG = "clang-include";          //$NON-NLS-1$
    /**
     * layoutlib.jar file
     */
    public static final String FN_LAYOUTLIB_JAR = "layoutlib.jar";                    //$NON-NLS-1$
    /**
     * widget list file
     */
    public static final String FN_WIDGETS = "widgets.txt";                            //$NON-NLS-1$
    /**
     * Intent activity actions list file
     */
    public static final String FN_INTENT_ACTIONS_ACTIVITY = "activity_actions.txt";   //$NON-NLS-1$
    /**
     * Intent broadcast actions list file
     */
    public static final String FN_INTENT_ACTIONS_BROADCAST = "broadcast_actions.txt"; //$NON-NLS-1$
    /**
     * Intent service actions list file
     */
    public static final String FN_INTENT_ACTIONS_SERVICE = "service_actions.txt";     //$NON-NLS-1$
    /**
     * Intent category list file
     */
    public static final String FN_INTENT_CATEGORIES = "categories.txt";               //$NON-NLS-1$

    /**
     * Resources folder name, i.e. "res".
     */
    public static final String FD_RESOURCES = "res";                    //$NON-NLS-1$
    /**
     * Assets folder name, i.e. "assets"
     */
    public static final String FD_ASSETS = "assets";                    //$NON-NLS-1$
    /**
     * Default source folder name in an SDK project, i.e. "src".
     * <p/>
     * Note: this is not the same as {@link #FD_PKG_SOURCES}
     * which is an SDK sources folder for packages.
     */
    public static final String FD_SOURCES = "src";                      //$NON-NLS-1$
    /**
     * Default main source set folder name, i.e. "main"
     */
    public static final String FD_MAIN = "main";                        //$NON-NLS-1$
    /**
     * Default test source set folder name, i.e. "androidTest"
     */
    public static final String FD_TEST = "androidTest";                 //$NON-NLS-1$
    /**
     * Default java code folder name, i.e. "java"
     */
    public static final String FD_JAVA = "java";                        //$NON-NLS-1$
    /**
     * Default native code folder name, i.e. "jni"
     */
    public static final String FD_JNI = "jni";                          //$NON-NLS-1$
    /**
     * Default gradle folder name, i.e. "gradle"
     */
    public static final String FD_GRADLE = "gradle";                    //$NON-NLS-1$
    /**
     * Default gradle wrapper folder name, i.e. "gradle/wrapper"
     */
    public static final String FD_GRADLE_WRAPPER = FD_GRADLE + File.separator + "wrapper";
    //$NON-NLS-1$
    /**
     * Default generated source folder name, i.e. "gen"
     */
    public static final String FD_GEN_SOURCES = "gen";                  //$NON-NLS-1$
    /**
     * Default native library folder name inside the project, i.e. "libs"
     * While the folder inside the .apk is "lib", we call that one libs because
     * that's what we use in ant for both .jar and .so and we need to make the 2 development ways
     * compatible.
     */
    public static final String FD_NATIVE_LIBS = "libs";                 //$NON-NLS-1$
    /**
     * Native lib folder inside the APK: "lib"
     */
    public static final String FD_APK_NATIVE_LIBS = "lib";              //$NON-NLS-1$
    /**
     * Default output folder name, i.e. "bin"
     */
    public static final String FD_OUTPUT = "bin";                       //$NON-NLS-1$
    /**
     * Classes output folder name, i.e. "classes"
     */
    public static final String FD_CLASSES_OUTPUT = "classes";           //$NON-NLS-1$
    /**
     * proguard output folder for mapping, etc.. files
     */
    public static final String FD_PROGUARD = "proguard";                //$NON-NLS-1$
    /**
     * aidl output folder for copied aidl files
     */
    public static final String FD_AIDL = "aidl";                        //$NON-NLS-1$
    /**
     * rs Libs output folder for support mode
     */
    public static final String FD_RS_LIBS = "rsLibs";                   //$NON-NLS-1$
    /**
     * rs Libs output folder for support mode
     */
    public static final String FD_RS_OBJ = "rsObj";                     //$NON-NLS-1$
    /**
     * jars folder
     */
    public static final String FD_JARS = "jars";                        //$NON-NLS-1$
    /* Folder Names for the Android SDK */
    /**
     * Name of the SDK platforms folder.
     */
    public static final String FD_PLATFORMS = "platforms";              //$NON-NLS-1$
    /**
     * Name of the SDK addons folder.
     */
    public static final String FD_ADDONS = "add-ons";                   //$NON-NLS-1$
    /**
     * Name of the SDK system-images folder.
     */
    public static final String FD_SYSTEM_IMAGES = "system-images";      //$NON-NLS-1$
    /**
     * Name of the SDK sources folder where source packages are installed.
     * <p/>
     * Note this is not the same as {@link #FD_SOURCES} which is the folder name where sources
     * are installed inside a project.
     */
    public static final String FD_PKG_SOURCES = "sources";              //$NON-NLS-1$

    /**
     * Name of the Java resources folder, i.e. "resources"
     */
    public static final String FD_JAVA_RES = "resources";               //$NON-NLS-1$
    /**
     * Name of the SDK resources folder, i.e. "res"
     */
    public static final String FD_RES = "res";                          //$NON-NLS-1$
    /**
     * Name of the SDK font folder, i.e. "fonts"
     */
    public static final String FD_FONTS = "fonts";                      //$NON-NLS-1$
    /**
     * Name of the android sources directory and the root of the SDK sources package folder.
     */
    public static final String FD_ANDROID_SOURCES = "sources";          //$NON-NLS-1$
    /**
     * Name of the addon libs folder.
     */
    public static final String FD_ADDON_LIBS = "libs";                  //$NON-NLS-1$
    public static final String DOT_CLASS = ".class";
    public static final String DOT_DEX = ".dex";
    public static final String DOT_JAR = ".jar";

    /**
     * Extension of the Application package Files, i.e. "apk".
     */
    public static final String EXT_ANDROID_PACKAGE = "apk";
    /**
     * Extension of the InstantApp package Files, i.e. "iapk".
     */
    public static final String EXT_INSTANTAPP_PACKAGE = "iapk";
    /**
     * Extension for Android archive files
     */
    public static final String EXT_AAR = "aar";
    /**
     * Extension for Android Privacy Sandbox Sdk archives
     */
    public static final String EXT_ASAR = "asar";
    /**
     * Extension for Android Privacy Sandbox Sdk bundles
     */
    public static final String EXT_ASB = "asb";
    /**
     * Extension for Android atom files.
     */
    public static final String EXT_ATOM = "atom";
    /**
     * Extension of java files, i.e. "java"
     */
    public static final String EXT_JAVA = "java";
    /**
     * Extension of compiled java files, i.e. "class"
     */
    public static final String EXT_CLASS = "class";
    /**
     * Extension of xml files, i.e. "xml"
     */
    public static final String EXT_XML = "xml";
    /**
     * Extension of gradle files, i.e. "gradle"
     */
    public static final String EXT_GRADLE = "gradle";
    /**
     * Extension of Kotlin gradle files, i.e. "gradle.kts"
     */
    public static final String EXT_GRADLE_KTS = "gradle.kts";
    /**
     * Extension of jar files, i.e. "jar"
     */
    public static final String EXT_JAR = "jar";
    /**
     * Extension of ZIP files, i.e. "zip"
     */
    public static final String EXT_ZIP = "zip";
    /**
     * Extension of aidl files, i.e. "aidl"
     */
    public static final String EXT_AIDL = "aidl";
    /**
     * Extension of Renderscript files, i.e. "rs"
     */
    public static final String EXT_RS = "rs";
    /**
     * Extension of Renderscript files, i.e. "rsh"
     */
    public static final String EXT_RSH = "rsh";
    /**
     * Extension of FilterScript files, i.e. "fs"
     */
    public static final String EXT_FS = "fs";
    /**
     * Extension of Renderscript bitcode files, i.e. "bc"
     */
    public static final String EXT_BC = "bc";
    /**
     * Extension of dependency files, i.e. "d"
     */
    public static final String EXT_DEP = "d";
    /**
     * Extension of native libraries, i.e. "so"
     */
    public static final String EXT_NATIVE_LIB = "so";
    /**
     * Extension of dex files, i.e. "dex"
     */
    public static final String EXT_DEX = "dex";
    /**
     * Extension for temporary resource files, ie "ap_
     */
    public static final String EXT_RES = "ap_";
    /**
     * Extension for pre-processable images. Right now pngs
     */
    public static final String EXT_PNG = "png";
    /**
     * Extension of app bundle files, i.e. "aab"
     */
    public static final String EXT_APP_BUNDLE = "aab";

    public static final String EXT_HPROF = "hprof";
    public static final String EXT_GZ = "gz";

    public static final String EXT_JSON = "json";

    public static final String EXT_CSV = "csv";
    public static final String CODENAME_RELEASE = "release";
    @NotNull
    public static final String FD_DEX = "dex";
    /** Skin layout file */
    public static final String FN_SKIN_LAYOUT = "layout";

    /** name of the art runtime profile in aar files (located in the android private assets) */
    public static final String FN_ART_PROFILE = "baseline-prof.txt";

    public static final String FN_BINART_ART_PROFILE_FOLDER_IN_APK = "assets/dexopt";
    public static final String FN_BINART_ART_PROFILE_FOLDER_IN_AAB =
            "com.android.tools.build.profiles";
    public static final String FN_BINARY_ART_PROFILE = "baseline.prof";
    public static final String FN_BINARY_ART_PROFILE_METADATA = "baseline.profm";

    /** Intermediates folder under the build directory */
    public static final String FD_INTERMEDIATES = "intermediates";
    /** logs folder under the build directory */
    public static final String FD_LOGS = "logs";
    /** outputs folder under the build directory */
    public static final String FD_OUTPUTS = "outputs";
    /** generated folder under the build directory */
    public static final String FD_GENERATED = "generated";
    /** Dot-Extension of the Application package Files, i.e. ".apk". */
    public static final String DOT = ".";
    public static final String DOT_ANDROID_PACKAGE = DOT + EXT_ANDROID_PACKAGE;
}
