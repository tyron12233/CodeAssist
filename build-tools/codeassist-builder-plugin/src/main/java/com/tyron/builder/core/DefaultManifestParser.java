package com.tyron.builder.core;

import static com.android.SdkConstants.ATTR_EXTRACT_NATIVE_LIBS;
import static com.android.SdkConstants.ATTR_FUNCTIONAL_TEST;
import static com.android.SdkConstants.ATTR_HANDLE_PROFILING;
import static com.android.SdkConstants.ATTR_LABEL;
import static com.android.SdkConstants.ATTR_MIN_SDK_VERSION;
import static com.android.SdkConstants.ATTR_NAME;
import static com.android.SdkConstants.ATTR_PACKAGE;
import static com.android.SdkConstants.ATTR_SPLIT;
import static com.android.SdkConstants.ATTR_TARGET_PACKAGE;
import static com.android.SdkConstants.ATTR_TARGET_SDK_VERSION;
import static com.android.SdkConstants.ATTR_USE_EMBEDDED_DEX;
import static com.android.SdkConstants.ATTR_VERSION_CODE;
import static com.android.SdkConstants.ATTR_VERSION_NAME;
import static com.android.SdkConstants.NS_RESOURCES;
import static com.android.SdkConstants.TAG_APPLICATION;
import static com.android.SdkConstants.TAG_INSTRUMENTATION;
import static com.android.SdkConstants.TAG_MANIFEST;
import static com.android.SdkConstants.TAG_USES_SDK;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.APP_EXTRACT_NATIVE_LIBS;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.APP_USE_EMBEDDED_DEX;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.INST_FUNCTIONAL_TEST;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.INST_HANDLE_PROF;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.INST_LABEL;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.INST_NAME;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.INST_TARGET_PKG;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.MIN_SDK_VERSION;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.PACKAGE;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.SPLIT;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.TARGET_SDK_VERSION;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.VERSION_CODE;
import static com.tyron.builder.core.DefaultManifestParser.Attribute.VERSION_NAME;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.manifmerger.PlaceholderHandler;
import com.tyron.builder.errors.IssueReporter;
import com.android.utils.XmlUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import java.io.File;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.openjdk.javax.xml.parsers.SAXParser;
import org.openjdk.javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Implementation of the {@link ManifestAttributeSupplier}.
 *
 * <p>This is meant to be a quick parser to create the building model, and is thread-safe.
 */
@Deprecated
public class DefaultManifestParser implements ManifestAttributeSupplier {

    private static final SAXParserFactory PARSER_FACTORY = SAXParserFactory.newInstance();

    static {
        XmlUtils.configureSaxFactory(PARSER_FACTORY, true, false);
    }

    private static final Object lock = new Object();

    @NonNull private final File manifestFile;

    @NonNull
    private final Map<Attribute, String> attributeValues = Maps.newEnumMap(Attribute.class);

    private final IssueReporter issueReporter;
    private boolean initialized = false;

    @NonNull private BooleanSupplier canParseManifest;

    private boolean isManifestFileRequired;

    /**
     * Builds instance of the parser, and parses the supplied file. The manifest is lazily parsed
     * and should typically only be parsed during the execution phase.
     *
     * @param manifestFile manifest to be parsed.
     * @param canParseManifest whether the manifest can currently be parsed.
     * @param isManifestFileRequired whether the manifest file is required to exist
     * @param issueReporter IssueReporter
     */
    public DefaultManifestParser(
            @NonNull File manifestFile,
            @NonNull BooleanSupplier canParseManifest,
            boolean isManifestFileRequired,
            @Nullable IssueReporter issueReporter) {
        this.manifestFile = manifestFile;
        this.canParseManifest = canParseManifest;
        this.isManifestFileRequired = isManifestFileRequired;
        this.issueReporter = issueReporter;
    }

    @Override
    public boolean isManifestFileRequired() {
        return isManifestFileRequired;
    }

    /** Gets the package name for the manifest file processed by this parser. */
    @Nullable
    @Override
    public String getPackage() {
        init();
        return attributeValues.get(PACKAGE);
    }

    /**
     * Gets the split name for the manifest file processed by this parser.
     */
    @Nullable
    @Override
    public String getSplit() {
        init();

        return attributeValues.get(SPLIT);
    }

    /**
     * Gets the minimum sdk version for the manifest file processed by this parser.
     */
    @Override
    @NonNull
    public Object getMinSdkVersion() {
        init();
        String minSdkVersion = attributeValues.get(MIN_SDK_VERSION);
        return parseIntValueOrDefault(minSdkVersion, minSdkVersion, null);
    }

    /**
     * Gets the target sdk version for the manifest file processed by this parser.
     */
    @Override
    @NonNull
    public Object getTargetSdkVersion() {
        init();
        String targetSdkVersion = attributeValues.get(TARGET_SDK_VERSION);
        return parseIntValueOrDefault(targetSdkVersion, targetSdkVersion, null);
    }

    /**
     * Gets the instrumentation runner for the manifest file processed by this parser.
     */
    @Nullable
    @Override
    public String getInstrumentationRunner() {
        init();
        return attributeValues.get(INST_NAME);
    }

    /**
     * Gets the target package for instrumentation in the manifest file processed by this parser.
     */
    @Nullable
    @Override
    public String getTargetPackage() {
        init();
        return attributeValues.get(INST_TARGET_PKG);
    }

    /**
     * Gets the functionalTest for instrumentation in the manifest file processed by this parser.
     */
    @Nullable
    @Override
    public Boolean getFunctionalTest() {
        init();
        String functionalTest = attributeValues.get(INST_FUNCTIONAL_TEST);
        return parseBoolean(functionalTest);
    }

    /**
     * Gets the handleProfiling for instrumentation in the manifest file processed by this parser.
     */
    @Nullable
    @Override
    public Boolean getHandleProfiling() {
        init();
        String handleProfiling = attributeValues.get(INST_HANDLE_PROF);
        return parseBoolean(handleProfiling);
    }

    /**
     * Gets the testLabel for instrumentation in the manifest file processed by this parser.
     */
    @Nullable
    @Override
    public String getTestLabel() {
        init();
        return attributeValues.get(INST_LABEL);
    }

    @Nullable
    @Override
    public Boolean getExtractNativeLibs() {
        init();
        String extractNativeLibs = attributeValues.get(Attribute.APP_EXTRACT_NATIVE_LIBS);
        return parseBoolean(extractNativeLibs);
    }

    @Nullable
    @Override
    public Boolean getUseEmbeddedDex() {
        init();
        String useEmbeddedDex = attributeValues.get(APP_USE_EMBEDDED_DEX);
        return parseBoolean(useEmbeddedDex);
    }

    /**
     * If {@code value} is {@code null}, it returns {@code ifNull}. Otherwise it tries to parse the
     * {@code value} to {@link Integer}. If parsing the {@link Integer} fails, it will return {@code
     * ifNotInt} value.
     *
     * @param value    to be parsed
     * @param ifNotInt value returned if value is non {@code null} and it is not {@code int} value
     * @param ifNull   value returned if supplied value is {@code null}
     * @return final value according to the rules described above
     */
    private static Object parseIntValueOrDefault(String value, Object ifNotInt, Object ifNull) {
        if (value != null) {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ignored) {
                return ifNotInt;
            }
        } else {
            return ifNull;
        }
    }

    @Nullable
    private static Boolean parseBoolean(String value) {
        if (value != null) {
            return Boolean.parseBoolean(value);
        } else {
            return null;
        }
    }

    enum Attribute {
        SPLIT,
        PACKAGE,
        VERSION_CODE,
        VERSION_NAME,
        INST_LABEL,
        INST_FUNCTIONAL_TEST,
        INST_NAME,
        INST_HANDLE_PROF,
        INST_TARGET_PKG,
        MIN_SDK_VERSION,
        TARGET_SDK_VERSION,
        APP_EXTRACT_NATIVE_LIBS,
        APP_USE_EMBEDDED_DEX,
        ;
    }

    /** Parse the file and store the result in a map. */
    private void init() {
        synchronized (lock) {
            if (!canParseManifest.getAsBoolean() && issueReporter != null) {
                // This is not an exception since we still want sync to succeed if this occurs.
                // Instead print the stack trace so that the developer will know how this occurred.
                String stackTrace = Joiner.on("\n").join(Thread.currentThread().getStackTrace());
                issueReporter.reportWarning(
                        IssueReporter.Type.MANIFEST_PARSED_DURING_CONFIGURATION,
                        "The manifest is being parsed during configuration. Please "
                                + "either remove android.disableConfigurationManifestParsing "
                                + "from build.gradle or remove any build configuration rules "
                                + "that read the android manifest file.\n"
                                + stackTrace);
            }
            if (!initialized) {
                if (!manifestFile.isFile()) {
                    if (isManifestFileRequired) {
                        throw new RuntimeException(
                                "Manifest file does not exist: " + manifestFile.getAbsolutePath());
                    } else {
                        return;
                    }
                }
                DefaultHandler handler =
                        new DefaultHandler() {
                            @Override
                            public void startElement(
                                    String uri,
                                    String localName,
                                    String qName,
                                    Attributes attributes)
                                    throws SAXException {
                                if (uri == null || uri.isEmpty()) {
                                    if (TAG_MANIFEST.equals(localName)) {
                                        putValue(SPLIT, attributes.getValue("", ATTR_SPLIT));
                                        putValue(PACKAGE, attributes.getValue("", ATTR_PACKAGE));
                                        putValue(
                                                VERSION_CODE,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_VERSION_CODE));
                                        putValue(
                                                VERSION_NAME,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_VERSION_NAME));
                                    } else if (TAG_INSTRUMENTATION.equals(localName)) {
                                        putValue(
                                                INST_LABEL,
                                                attributes.getValue(NS_RESOURCES, ATTR_LABEL));
                                        putValue(
                                                INST_FUNCTIONAL_TEST,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_FUNCTIONAL_TEST));
                                        putValue(
                                                INST_NAME,
                                                attributes.getValue(NS_RESOURCES, ATTR_NAME));
                                        putValue(
                                                INST_HANDLE_PROF,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_HANDLE_PROFILING));
                                        putValue(
                                                INST_TARGET_PKG,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_TARGET_PACKAGE));
                                    } else if (TAG_USES_SDK.equals(localName)) {
                                        putValue(
                                                MIN_SDK_VERSION,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_MIN_SDK_VERSION));
                                        putValue(
                                                TARGET_SDK_VERSION,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_TARGET_SDK_VERSION));
                                    } else if (TAG_APPLICATION.equals(localName)) {
                                        putValue(
                                                APP_EXTRACT_NATIVE_LIBS,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_EXTRACT_NATIVE_LIBS));
                                        putValue(
                                                APP_USE_EMBEDDED_DEX,
                                                attributes.getValue(
                                                        NS_RESOURCES, ATTR_USE_EMBEDDED_DEX));
                                    }
                                }
                            }
                        };

                try {
                    SAXParser saxParser = XmlUtils.createSaxParser(PARSER_FACTORY);
                    saxParser.parse(manifestFile, handler);
                    initialized = true;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void putValue(@NonNull Attribute attribute, @Nullable String value) {
        if (value != null && !PlaceholderHandler.isPlaceHolder(value)) {
            attributeValues.put(attribute, value);
        }
    }
}
