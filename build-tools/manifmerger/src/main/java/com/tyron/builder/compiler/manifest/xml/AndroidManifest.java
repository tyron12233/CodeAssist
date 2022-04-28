package com.tyron.builder.compiler.manifest.xml;


import com.tyron.builder.compiler.manifest.SdkConstants;

/** Constants for the AndroidManifest.xml file. */
public final class AndroidManifest {

    public static final String NODE_MANIFEST = "manifest";
    public static final String NODE_APPLICATION = "application";
    public static final String NODE_ACTIVITY = "activity";
    public static final String NODE_ACTIVITY_ALIAS = "activity-alias";
    public static final String NODE_SERVICE = "service";
    public static final String NODE_RECEIVER = "receiver";
    public static final String NODE_PROVIDER = "provider";
    public static final String NODE_INTENT = "intent-filter";
    public static final String NODE_ACTION = "action";
    public static final String NODE_CATEGORY = "category";
    public static final String NODE_USES_SDK = "uses-sdk";
    public static final String NODE_PERMISSION = "permission";
    public static final String NODE_PERMISSION_TREE = "permission-tree";
    public static final String NODE_PERMISSION_GROUP = "permission-group";
    public static final String NODE_USES_PERMISSION = "uses-permission";
    public static final String NODE_INSTRUMENTATION = "instrumentation";
    public static final String NODE_USES_LIBRARY = "uses-library";
    public static final String NODE_SUPPORTS_SCREENS = "supports-screens";
    public static final String NODE_COMPATIBLE_SCREENS = "compatible-screens";
    public static final String NODE_USES_CONFIGURATION = "uses-configuration";
    public static final String NODE_USES_FEATURE = "uses-feature";
    public static final String NODE_METADATA = "meta-data";
    public static final String NODE_DATA = "data";
    public static final String NODE_GRANT_URI_PERMISSION = "grant-uri-permission";
    public static final String NODE_PATH_PERMISSION = "path-permission";
    public static final String NODE_SUPPORTS_GL_TEXTURE = "supports-gl-texture";

    public static final String ATTRIBUTE_PACKAGE = "package";
    public static final String ATTRIBUTE_VERSIONCODE = "versionCode";
    public static final String ATTRIBUTE_VERSIONNAME = "versionName";
    public static final String ATTRIBUTE_NAME = "name";
    public static final String ATTRIBUTE_MIME_TYPE = "mimeType";
    public static final String ATTRIBUTE_PORT = "port";
    public static final String ATTRIBUTE_REQUIRED = "required";
    public static final String ATTRIBUTE_GLESVERSION = "glEsVersion";
    public static final String ATTRIBUTE_PROCESS = "process";
    public static final String ATTRIBUTE_DEBUGGABLE = "debuggable";
    public static final String ATTRIBUTE_HASCODE = "hasCode";
    public static final String ATTRIBUTE_LABEL = "label";
    public static final String ATTRIBUTE_ICON = "icon";
    public static final String ATTRIBUTE_MIN_SDK_VERSION = "minSdkVersion";
    public static final String ATTRIBUTE_TARGET_SDK_VERSION = "targetSdkVersion";
    public static final String ATTRIBUTE_TARGET_PACKAGE = "targetPackage";
    public static final String ATTRIBUTE_FUNCTIONAL_TEST = "functionalTest";
    public static final String ATTRIBUTE_HANDLE_PROFILING = "handleProfiling";
    public static final String ATTRIBUTE_INSTRUMENTATION_LABEL = "label";
    public static final String ATTRIBUTE_TARGET_ACTIVITY = "targetActivity";
    public static final String ATTRIBUTE_MANAGE_SPACE_ACTIVITY = "manageSpaceActivity";
    public static final String ATTRIBUTE_EXPORTED = "exported";
    public static final String ATTRIBUTE_RESIZEABLE = "resizeable";
    public static final String ATTRIBUTE_ANYDENSITY = "anyDensity";
    public static final String ATTRIBUTE_SMALLSCREENS = "smallScreens";
    public static final String ATTRIBUTE_NORMALSCREENS = "normalScreens";
    public static final String ATTRIBUTE_LARGESCREENS = "largeScreens";
    public static final String ATTRIBUTE_REQ_5WAYNAV = "reqFiveWayNav";
    public static final String ATTRIBUTE_REQ_NAVIGATION = "reqNavigation";
    public static final String ATTRIBUTE_REQ_HARDKEYBOARD = "reqHardKeyboard";
    public static final String ATTRIBUTE_REQ_KEYBOARDTYPE = "reqKeyboardType";
    public static final String ATTRIBUTE_REQ_TOUCHSCREEN = "reqTouchScreen";
    public static final String ATTRIBUTE_THEME = "theme";
    public static final String ATTRIBUTE_BACKUP_AGENT = "backupAgent";
    public static final String ATTRIBUTE_PARENT_ACTIVITY_NAME = "parentActivityName";
    public static final String ATTRIBUTE_SUPPORTS_RTL = "supportsRtl";
    public static final String ATTRIBUTE_UI_OPTIONS = "uiOptions";
    public static final String ATTRIBUTE_VALUE = "value";
    public static final String ATTRIBUTE_EXTRACT_NATIVE_LIBS = "extractNativeLibs";
    public static final String ATTRIBUTE_SPLIT = "split";
    public static final String ATTRIBUTE_RESIZEABLE_ACTIVITY = "resizeableActivity";
    public static final String ATTRIBUTE_SCREEN_ORIENTATION = "screenOrientation";
    public static final String ATTRIBUTE_ISOLATED_PROCESS = "isolatedProcess";
    public static final String ATTRIBUTE_ENABLED = "enabled";

    public static final String ATTRIBUTE_PERMISSION = "permission";

    public static final String VALUE_PARENT_ACTIVITY =
            SdkConstants.ANDROID_SUPPORT_PKG_PREFIX + "PARENT_ACTIVITY";

}
