package com.tyron.builder.compiler.manifest.xml;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.builder.compiler.manifest.resources.Keyboard;
import com.tyron.builder.compiler.manifest.resources.Navigation;
import com.tyron.builder.compiler.manifest.resources.TouchScreen;
import com.tyron.builder.compiler.manifest.xml.ManifestData.Activity;
import com.tyron.builder.compiler.manifest.xml.ManifestData.Instrumentation;
import com.tyron.builder.compiler.manifest.xml.ManifestData.SupportsScreens;
import com.tyron.builder.compiler.manifest.xml.ManifestData.UsesConfiguration;
import com.tyron.builder.compiler.manifest.xml.ManifestData.UsesFeature;
import com.tyron.builder.compiler.manifest.xml.ManifestData.UsesLibrary;
import com.tyron.builder.util.XmlUtils;

import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.openjdk.javax.xml.parsers.SAXParser;
import org.openjdk.javax.xml.parsers.SAXParserFactory;
import org.openjdk.javax.xml.transform.Transformer;
import org.openjdk.javax.xml.transform.TransformerConfigurationException;
import org.openjdk.javax.xml.transform.TransformerException;
import org.openjdk.javax.xml.transform.TransformerFactory;
import org.openjdk.javax.xml.transform.dom.DOMSource;
import org.openjdk.javax.xml.transform.sax.SAXResult;
import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Full Manifest parser that parses the manifest in details, including activities, instrumentations,
 * support-screens, and uses-configuration.
 */
public class AndroidManifestParser {

    private static final int LEVEL_TOP = 0;
    private static final int LEVEL_INSIDE_MANIFEST = 1;
    private static final int LEVEL_INSIDE_APPLICATION = 2;
    private static final int LEVEL_INSIDE_APP_COMPONENT = 3;
    private static final int LEVEL_INSIDE_INTENT_FILTER = 4;

    private static final String ACTION_MAIN = "android.intent.action.MAIN"; //$NON-NLS-1$
    private static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"; //$NON-NLS-1$

    public interface ManifestErrorHandler extends ErrorHandler {
        /**
         * Handles a parsing error and an optional line number.
         */
        void handleError(Exception exception, int lineNumber);

        /**
         * Checks that a class is valid and can be used in the Android Manifest.
         * <p>
         * Errors are put as {@code org.eclipse.core.resources.IMarker} on the manifest file.
         *
         * @param className the fully qualified name of the class to test.
         * @param superClassName the fully qualified name of the class it is supposed to extend.
         * @param testVisibility if <code>true</code>, the method will check the visibility of
         * the class or of its constructors.
         */
        void checkClass(Locator locator, String className, String superClassName,
                        boolean testVisibility);
    }

    /**
     * XML error and data handler used when parsing the AndroidManifest.xml file.
     * <p>
     * During parsing this will fill up the {@link ManifestData} object given to the constructor
     * and call out errors to the given {@link ManifestErrorHandler}.
     */
    private static class ManifestHandler extends DefaultHandler {

        // --- temporary data/flags used during parsing
        @Nullable private final ManifestData mManifestData;
        @Nullable private final ManifestErrorHandler mErrorHandler;
        private int mCurrentLevel = 0;
        private int mValidLevel = 0;
        private Activity mCurrentActivity = null;
        private Locator mLocator;

        /**
         * Creates a new {@link ManifestHandler}.
         *
         * @param manifestData Class containing the manifest info obtained during the parsing.
         * @param errorHandler An optional error handler.
         */
        ManifestHandler(
                @Nullable ManifestData manifestData, @Nullable ManifestErrorHandler errorHandler) {
            super();
            mManifestData = manifestData;
            mErrorHandler = errorHandler;
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#setDocumentLocator(org.xml.sax.Locator)
         */
        @Override
        public void setDocumentLocator(Locator locator) {
            mLocator = locator;
            super.setDocumentLocator(locator);
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String,
         * java.lang.String, org.xml.sax.Attributes)
         */
        @Override
        public void startElement(String uri, String localName, String name, Attributes attributes)
                throws SAXException {
            try {
                if (mManifestData == null) {
                    return;
                }

                // if we're at a valid level
                if (mValidLevel == mCurrentLevel) {
                    String value;
                    switch (mValidLevel) {
                        case LEVEL_TOP:
                            if (AndroidManifest.NODE_MANIFEST.equals(localName)) {
                                // lets get the package name.
                                mManifestData.mPackage =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_PACKAGE,
                                                false /* hasNamespace */);

                                // and the versionCode
                                String tmp =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_VERSIONCODE,
                                                true);
                                if (tmp != null) {
                                    try {
                                        mManifestData.mVersionCode = Integer.valueOf(tmp);
                                    } catch (NumberFormatException e) {
                                        // keep null in the field.
                                    }
                                }
                                // and the versionName
                                mManifestData.mVersionName =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_VERSIONNAME,
                                                true /* hasNamespace */);
                                mValidLevel++;
                            }
                            break;
                        case LEVEL_INSIDE_MANIFEST:
                            if (AndroidManifest.NODE_APPLICATION.equals(localName)) {
                                processApplicationNode(attributes);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_USES_SDK.equals(localName)) {
                                mManifestData.setMinSdkVersionString(
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_MIN_SDK_VERSION,
                                                true /* hasNamespace */));
                                mManifestData.setTargetSdkVersionString(
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_TARGET_SDK_VERSION,
                                                true /* hasNamespace */));
                            } else if (AndroidManifest.NODE_INSTRUMENTATION.equals(localName)) {
                                processInstrumentationNode(attributes);

                            } else if (AndroidManifest.NODE_SUPPORTS_SCREENS.equals(localName)) {
                                processSupportsScreensNode(attributes);

                            } else if (AndroidManifest.NODE_USES_CONFIGURATION.equals(localName)) {
                                processUsesConfiguration(attributes);

                            } else if (AndroidManifest.NODE_USES_FEATURE.equals(localName)) {
                                UsesFeature feature = new UsesFeature();

                                // get the name
                                value =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_NAME,
                                                true /* hasNamespace */);
                                if (value != null) {
                                    feature.mName = value;
                                }

                                // read the required attribute
                                value =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_REQUIRED,
                                                true /*hasNamespace*/);
                                if (value != null) {
                                    Boolean b = Boolean.valueOf(value);
                                    if (b != null) {
                                        feature.mRequired = b;
                                    }
                                }

                                // read the gl es attribute
                                value =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_GLESVERSION,
                                                true /*hasNamespace*/);
                                if (value != null) {
                                    try {
                                        int version = Integer.decode(value);
                                        feature.mGlEsVersion = version;
                                    } catch (NumberFormatException e) {
                                        // ignore
                                    }
                                }

                                mManifestData.mFeatures.add(feature);
                            } else if (AndroidManifest.NODE_PERMISSION.equals(localName)) {
                                processPermissionNode(attributes);
                            }
                            break;
                        case LEVEL_INSIDE_APPLICATION:
                            if (AndroidManifest.NODE_ACTIVITY.equals(localName)
                                    || AndroidManifest.NODE_ACTIVITY_ALIAS.equals(localName)) {
                                processActivityNode(attributes);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_SERVICE.equals(localName)) {
                                processNode(attributes, SdkConstants.CLASS_SERVICE, localName);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_RECEIVER.equals(localName)) {
                                processNode(
                                        attributes,
                                        SdkConstants.CLASS_BROADCASTRECEIVER,
                                        localName);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_PROVIDER.equals(localName)) {
                                processNode(
                                        attributes, SdkConstants.CLASS_CONTENTPROVIDER, localName);
                                mValidLevel++;
                            } else if (AndroidManifest.NODE_USES_LIBRARY.equals(localName)) {
                                value =
                                        getAttributeValue(
                                                attributes,
                                                AndroidManifest.ATTRIBUTE_NAME,
                                                true /* hasNamespace */);
                                if (value != null) {
                                    UsesLibrary library = new UsesLibrary();
                                    library.mName = value;

                                    // read the required attribute
                                    value =
                                            getAttributeValue(
                                                    attributes,
                                                    AndroidManifest.ATTRIBUTE_REQUIRED,
                                                    true /*hasNamespace*/);
                                    if (value != null) {
                                        Boolean b = Boolean.valueOf(value);
                                        if (b != null) {
                                            library.mRequired = b;
                                        }
                                    }

                                    mManifestData.mLibraries.add(library);
                                }
                            }
                            break;
                        case LEVEL_INSIDE_APP_COMPONENT:
                            // only process this level if we are in an activity
                            if (mCurrentActivity != null &&
                                    AndroidManifest.NODE_INTENT.equals(localName)) {
                                mCurrentActivity.resetIntentFilter();
                                mValidLevel++;
                            }
                            break;
                        case LEVEL_INSIDE_INTENT_FILTER:
                            if (mCurrentActivity != null) {
                                if (AndroidManifest.NODE_ACTION.equals(localName)) {
                                    // get the name attribute
                                    String action = getAttributeValue(attributes,
                                            AndroidManifest.ATTRIBUTE_NAME,
                                            true /* hasNamespace */);
                                    if (action != null) {
                                        mCurrentActivity.setHasAction(true);
                                        mCurrentActivity.setHasMainAction(
                                                ACTION_MAIN.equals(action));
                                    }
                                } else if (AndroidManifest.NODE_CATEGORY.equals(localName)) {
                                    String category = getAttributeValue(attributes,
                                            AndroidManifest.ATTRIBUTE_NAME,
                                            true /* hasNamespace */);
                                    if (CATEGORY_LAUNCHER.equals(category)) {
                                        mCurrentActivity.setHasLauncherCategory(true);
                                    }
                                }

                                // no need to increase mValidLevel as we don't process anything
                                // below this level.
                            }
                            break;
                    }
                }

                mCurrentLevel++;
            } finally {
                super.startElement(uri, localName, name, attributes);
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String,
         * java.lang.String)
         */
        @Override
        public void endElement(String uri, String localName, String name) throws SAXException {
            try {
                if (mManifestData == null) {
                    return;
                }

                // decrement the levels.
                if (mValidLevel == mCurrentLevel) {
                    mValidLevel--;
                }
                mCurrentLevel--;

                // if we're at a valid level
                // process the end of the element
                if (mValidLevel == mCurrentLevel) {
                    switch (mValidLevel) {
                        case LEVEL_INSIDE_APPLICATION:
                            mCurrentActivity = null;
                            break;
                        case LEVEL_INSIDE_APP_COMPONENT:
                            // if we found both a main action and a launcher category, this is our
                            // launcher activity!
                            if (mManifestData.mLauncherActivity == null &&
                                    mCurrentActivity != null &&
                                    mCurrentActivity.isHomeActivity() &&
                                    mCurrentActivity.isExported()) {
                                mManifestData.mLauncherActivity = mCurrentActivity;
                            }
                            break;
                        default:
                            break;
                    }

                }
            } finally {
                super.endElement(uri, localName, name);
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#error(org.xml.sax.SAXParseException)
         */
        @Override
        public void error(SAXParseException e) {
            if (mErrorHandler != null) {
                mErrorHandler.handleError(e, e.getLineNumber());
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#fatalError(org.xml.sax.SAXParseException)
         */
        @Override
        public void fatalError(SAXParseException e) {
            if (mErrorHandler != null) {
                mErrorHandler.handleError(e, e.getLineNumber());
            }
        }

        /* (non-Javadoc)
         * @see org.xml.sax.helpers.DefaultHandler#warning(org.xml.sax.SAXParseException)
         */
        @Override
        public void warning(SAXParseException e) throws SAXException {
            if (mErrorHandler != null) {
                mErrorHandler.warning(e);
            }
        }

        /**
         * Processes the application node.
         *
         * @param attributes the attributes for the application node.
         */
        private void processApplicationNode(Attributes attributes) {

            String value =
                    getAttributeValue(
                            attributes, AndroidManifest.ATTRIBUTE_PROCESS, true /* hasNamespace */);
            if (value != null) {
                mManifestData.addProcessName(value);
                mManifestData.mDefaultProcess = value;
            }

            value =
                    getAttributeValue(
                            attributes,
                            AndroidManifest.ATTRIBUTE_DEBUGGABLE,
                            true /* hasNamespace*/);
            if (value != null) {
                mManifestData.mDebuggable = Boolean.parseBoolean(value);
            }

            value =
                    getAttributeValue(
                            attributes, AndroidManifest.ATTRIBUTE_NAME, true /* hasNamespace*/);

            if (value != null) {
                mManifestData.mKeepClasses.add(
                        new ManifestData.KeepClass(
                                combinePackageAndClassName(mManifestData.mPackage, value),
                                null,
                                AndroidManifest.NODE_APPLICATION));
            }

            value =
                    getAttributeValue(
                            attributes,
                            AndroidManifest.ATTRIBUTE_BACKUP_AGENT,
                            true /* hasNamespace*/);

            if (value != null) {
                mManifestData.mKeepClasses.add(
                        new ManifestData.KeepClass(
                                combinePackageAndClassName(mManifestData.mPackage, value),
                                null,
                                AndroidManifest.ATTRIBUTE_BACKUP_AGENT));
            }

            value = getAttributeValue(
                    attributes,
                    AndroidManifest.ATTRIBUTE_THEME,
                    true);
            if (value != null) {
                mManifestData.setTheme(value);
            }
        }

        /**
         * Processes the activity node.
         *
         * @param attributes the attributes for the activity node.
         */
        private void processActivityNode(Attributes attributes) {
            // lets get the activity name, and add it to the list
            String activityName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_NAME,
                    true /* hasNamespace */);
            if (activityName != null) {
                activityName = combinePackageAndClassName(mManifestData.mPackage, activityName);

                // get the exported flag.
                String exportedStr = getAttributeValue(attributes,
                        AndroidManifest.ATTRIBUTE_EXPORTED, true);
                boolean exported = exportedStr == null ||
                        exportedStr.toLowerCase(Locale.US).equals("true"); //$NON-NLS-1$
                mCurrentActivity = new Activity(activityName, exported);

                String theme = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_THEME, true);
                if (theme != null) {
                    mCurrentActivity.setTheme(theme);
                }

                mManifestData.mActivities.add(mCurrentActivity);

                if (mErrorHandler != null) {
                    mErrorHandler.checkClass(mLocator, activityName, SdkConstants.CLASS_ACTIVITY,
                            true /* testVisibility */);
                }
            } else {
                // no activity found! Aapt will output an error,
                // so we don't have to do anything
                mCurrentActivity = null;
            }

            String processName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_PROCESS,
                    true /* hasNamespace */);
            if (processName != null) {
                mManifestData.addProcessName(processName);
            }

            if (processName == null || processName.isEmpty()) {
                processName = mManifestData.getDefaultProcess();
            }

            if (activityName != null) {
                mManifestData.mKeepClasses.add(
                        new ManifestData.KeepClass(
                                activityName, processName, AndroidManifest.NODE_ACTIVITY));
            }
        }

        /**
         * Processes the service/receiver/provider nodes.
         *
         * @param attributes the attributes for the activity node.
         * @param superClassName the fully qualified name of the super class that this
         * @param localName the tag of the node node is representing
         */
        private void processNode(Attributes attributes, String superClassName, String localName) {
            // lets get the class name, and check it if required.
            String serviceName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_NAME,
                    true /* hasNamespace */);
            if (serviceName != null) {
                serviceName = combinePackageAndClassName(mManifestData.mPackage, serviceName);

                if (mErrorHandler != null) {
                    mErrorHandler.checkClass(mLocator, serviceName, superClassName,
                            false /* testVisibility */);
                }
            }

            String processName = getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_PROCESS,
                    true /* hasNamespace */);
            if (processName != null) {
                mManifestData.addProcessName(processName);
            }

            if (processName == null || processName.isEmpty()) {
                processName = mManifestData.getDefaultProcess();
            }

            if (serviceName != null) {
                mManifestData.mKeepClasses.add(
                        new ManifestData.KeepClass(serviceName, processName, localName));
            }
        }

        /**
         * Processes the instrumentation node.
         * @param attributes the attributes for the instrumentation node.
         */
        private void processInstrumentationNode(Attributes attributes) {
            // lets get the class name, and check it if required.
            String instrumentationName = getAttributeValue(attributes,
                    AndroidManifest.ATTRIBUTE_NAME,
                    true /* hasNamespace */);
            if (instrumentationName != null) {
                String instrClassName =
                        combinePackageAndClassName(mManifestData.mPackage, instrumentationName);
                String targetPackage = getAttributeValue(attributes,
                        AndroidManifest.ATTRIBUTE_TARGET_PACKAGE,
                        true /* hasNamespace */);
                mManifestData.mInstrumentations.add(
                        new Instrumentation(instrClassName, targetPackage));
                mManifestData.mKeepClasses.add(
                        new ManifestData.KeepClass(
                                instrClassName, null, AndroidManifest.NODE_INSTRUMENTATION));
                if (mErrorHandler != null) {
                    mErrorHandler.checkClass(mLocator, instrClassName,
                            SdkConstants.CLASS_INSTRUMENTATION, true /* testVisibility */);
                }
            }
        }

        /**
         * Processes the supports-screens node.
         * @param attributes the attributes for the supports-screens node.
         */
        private void processSupportsScreensNode(Attributes attributes) {
            mManifestData.mSupportsScreensFromManifest = new SupportsScreens();

            mManifestData.mSupportsScreensFromManifest.setResizeable(getAttributeBooleanValue(
                    attributes, AndroidManifest.ATTRIBUTE_RESIZEABLE, true /*hasNamespace*/));

            mManifestData.mSupportsScreensFromManifest.setAnyDensity(getAttributeBooleanValue(
                    attributes, AndroidManifest.ATTRIBUTE_ANYDENSITY, true /*hasNamespace*/));

            mManifestData.mSupportsScreensFromManifest.setSmallScreens(getAttributeBooleanValue(
                    attributes, AndroidManifest.ATTRIBUTE_SMALLSCREENS, true /*hasNamespace*/));

            mManifestData.mSupportsScreensFromManifest.setNormalScreens(getAttributeBooleanValue(
                    attributes, AndroidManifest.ATTRIBUTE_NORMALSCREENS, true /*hasNamespace*/));

            mManifestData.mSupportsScreensFromManifest.setLargeScreens(getAttributeBooleanValue(
                    attributes, AndroidManifest.ATTRIBUTE_LARGESCREENS, true /*hasNamespace*/));
        }

        /**
         * Processes the supports-screens node.
         * @param attributes the attributes for the supports-screens node.
         */
        private void processUsesConfiguration(Attributes attributes) {
            mManifestData.mUsesConfiguration = new UsesConfiguration();

            mManifestData.mUsesConfiguration.mReqFiveWayNav = getAttributeBooleanValue(
                    attributes,
                    AndroidManifest.ATTRIBUTE_REQ_5WAYNAV, true /*hasNamespace*/);
            mManifestData.mUsesConfiguration.mReqNavigation = Navigation.getEnum(
                    getAttributeValue(attributes,
                            AndroidManifest.ATTRIBUTE_REQ_NAVIGATION, true /*hasNamespace*/));
            mManifestData.mUsesConfiguration.mReqHardKeyboard = getAttributeBooleanValue(
                    attributes,
                    AndroidManifest.ATTRIBUTE_REQ_HARDKEYBOARD, true /*hasNamespace*/);
            mManifestData.mUsesConfiguration.mReqKeyboardType = Keyboard.getEnum(
                    getAttributeValue(attributes,
                            AndroidManifest.ATTRIBUTE_REQ_KEYBOARDTYPE, true /*hasNamespace*/));
            mManifestData.mUsesConfiguration.mReqTouchScreen = TouchScreen.getEnum(
                    getAttributeValue(attributes,
                            AndroidManifest.ATTRIBUTE_REQ_TOUCHSCREEN, true /*hasNamespace*/));
        }

        private void processPermissionNode(Attributes attributes) {
            mManifestData.mCustomPermissions.add(
                    getAttributeValue(attributes, AndroidManifest.ATTRIBUTE_NAME, true));
        }

        /**
         * Searches through the attributes list for a particular one and returns its value.
         *
         * @param attributes the attribute list to search through
         * @param attributeName the name of the attribute to look for.
         * @param hasNamespace indicates whether the attribute has an android namespace.
         * @return a String with the value or null if the attribute was not found.
         * @see SdkConstants#ANDROID_URI
         */
        private String getAttributeValue(
                Attributes attributes, String attributeName, boolean hasNamespace) {
            int count = attributes.getLength();
            for (int i = 0 ; i < count ; i++) {
                if (attributeName.equals(attributes.getLocalName(i))
                        && ((hasNamespace && SdkConstants.ANDROID_URI.equals(attributes.getURI(i)))
                        || (!hasNamespace && attributes.getURI(i).isEmpty()))) {
                    return attributes.getValue(i);
                }
            }

            return null;
        }

        /**
         * Searches through the attributes list for a particular one and returns its value as a
         * Boolean. If the attribute is not present, this will return null.
         *
         * @param attributes the attribute list to search through
         * @param attributeName the name of the attribute to look for.
         * @param hasNamespace indicates whether the attribute has an android namespace.
         * @return a String with the value or null if the attribute was not found.
         * @see SdkConstants#ANDROID_URI
         */
        private Boolean getAttributeBooleanValue(
                Attributes attributes, String attributeName, boolean hasNamespace) {
            int count = attributes.getLength();
            for (int i = 0 ; i < count ; i++) {
                if (attributeName.equals(attributes.getLocalName(i))
                        && ((hasNamespace && SdkConstants.ANDROID_URI.equals(attributes.getURI(i)))
                        || (!hasNamespace && attributes.getURI(i).isEmpty()))) {
                    String attr = attributes.getValue(i);
                    if (attr != null) {
                        return Boolean.valueOf(attr);
                    } else {
                        return null;
                    }
                }
            }

            return null;
        }

        /**
         * Combines a java package, with a class value from the manifest to make a fully qualified
         * class name
         *
         * @param javaPackage the java package from the manifest.
         * @param className the class name from the manifest.
         * @return the fully qualified class name.
         */
        @Nullable
        private static String combinePackageAndClassName(
                @Nullable String javaPackage, @Nullable String className) {
            if (className == null || className.isEmpty()) {
                return javaPackage;
            }
            if (javaPackage == null || javaPackage.isEmpty()) {
                return className;
            }

            // the class name can be a subpackage (starts with a '.'
            // char), a simple class name (no dot), or a full java package
            boolean startWithDot = (className.charAt(0) == '.');
            boolean hasDot = (className.indexOf('.') != -1);
            if (startWithDot || !hasDot) {

                // add the concatenation of the package and class name
                if (startWithDot) {
                    return javaPackage + className;
                } else {
                    return javaPackage + '.' + className;
                }
            } else {
                // just add the class as it should be a fully qualified java name.
                return className;
            }
        }
    }

    private static final SAXParserFactory sParserFactory;

    static {
        sParserFactory = XmlUtils.getConfiguredSaxFactory(true, false);
    }

    /**
     * Parses the Android Manifest, and returns a {@link ManifestData} object containing the result
     * of the parsing.
     *
     * @param manifestFile the {@link IAbstractFile} representing the manifest file.
     * @param gatherData indicates whether the parsing will extract data from the manifest. If false
     *     the method will always return null.
     * @param errorHandler an optional errorHandler.
     * @return A class containing the manifest info obtained during the parsing, or null on error.
     * @throws IOException If there was a problem parsing the file
     * @throws SAXException If any SAX errors occurred during processing.
     */
    public static ManifestData parse(
            IAbstractFile manifestFile, boolean gatherData, ManifestErrorHandler errorHandler)
            throws IOException, SAXException {
        if (manifestFile != null) {
            SAXParser parser;
            try {
                parser = XmlUtils.createSaxParser(sParserFactory);
            } catch (ParserConfigurationException | SAXException e) {
                throw new RuntimeException(e);
            }

            ManifestData data = null;
            if (gatherData) {
                data = new ManifestData();
            }

            ManifestHandler manifestHandler = new ManifestHandler(data, errorHandler);

            try (InputStream is = manifestFile.getContents()) {
                parser.parse(new InputSource(is), manifestHandler);
            } catch (StreamException e) {
                throw new IOException(e);
            }

            return data;
        }

        return null;
    }

    /**
     * Parses the Android Manifest, and returns an object containing the result of the parsing.
     *
     * <p>This is the equivalent of calling {@code parse(manifestFile, true, null)}.
     *
     * @param manifestFile the manifest file to parse.
     */
    public static ManifestData parse(IAbstractFile manifestFile) throws IOException, SAXException {
        return parse(manifestFile, true, null);
    }

    /**
     * Currently using reflection, nasty can be fixed later by moving the module
     */
    public static ManifestData parse(String xml) throws IOException, SAXException, TransformerException {
        Document document;
        try {
            Class<?> aClass = Class.forName("org.eclipse.lemminx.dom.DOMParser");
            Method getInstance = aClass.getDeclaredMethod("getInstance");
            Object parser = getInstance.invoke(null);

            Class<?> extensionManager =
                    Class.forName("org.eclipse.lemminx.uriresolver.URIResolverExtensionManager");
            Method parse =
                    aClass.getDeclaredMethod("parse", String.class, String.class, extensionManager);
            document = (Document) parse.invoke(parser, xml, "", null);
        } catch (Throwable e) {
            throw new Error(e);
        }
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();

        ManifestData data = new ManifestData();
        ManifestHandler manifestHandler = new ManifestHandler(data, null);
        transformer.transform(new DOMSource(document),
                              new SAXResult(manifestHandler));
        return data;
    }


    /**
     * Parses the Android Manifest from an {@link InputStream}, and returns a {@link ManifestData}
     * object containing the result of the parsing.
     *
     * @param manifestFileStream the {@link InputStream} representing the manifest file.
     * @return A class containing the manifest info obtained during the parsing or null on error.
     */
    public static ManifestData parse(InputStream manifestFileStream)
            throws ParserConfigurationException, SAXException, IOException {
        if (manifestFileStream != null) {
            SAXParser parser = XmlUtils.createSaxParser(sParserFactory);

            ManifestData data = new ManifestData();

            ManifestHandler manifestHandler = new ManifestHandler(data, new ManifestErrorHandler() {
                @Override
                public void handleError(Exception exception, int lineNumber) {

                }

                @Override
                public void checkClass(Locator locator,
                                       String className,
                                       String superClassName,
                                       boolean testVisibility) {

                }

                @Override
                public void warning(SAXParseException e) throws SAXException {

                }

                @Override
                public void error(SAXParseException e) throws SAXException {

                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {

                }
            });
            parser.parse(new InputSource(manifestFileStream), manifestHandler);

            return data;
        }

        return null;
    }

    public static ManifestData parse(@NotNull File manifestFile) throws IOException {
        return parse(manifestFile.toPath());
    }

    @NotNull
    public static ManifestData parse(@NotNull Path manifestFile) throws IOException {
        try (InputStream is = new BufferedInputStream(Files.newInputStream(manifestFile))) {
            return parse(is);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException(e);
        }
    }
}

