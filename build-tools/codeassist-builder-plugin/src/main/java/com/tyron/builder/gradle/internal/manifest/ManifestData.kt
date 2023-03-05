package com.tyron.builder.gradle.internal.manifest

import com.android.SdkConstants
import com.tyron.builder.errors.IssueReporter
import org.openjdk.javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.util.function.BooleanSupplier
import javax.xml.parsers.ParserConfigurationException

/**
 * Represents the data parser from a manifest file.
 *
 * This is meant by to used with [ManifestDataProvider]
 */
class ManifestData(

    /**
     * Returns the package name from the manifest file.
     *
     * @return the package name or null if not found.
     */
    var packageName: String? = null,

    /**
     * Returns the split name from the manifest file.
     *
     * @return the split name or null if not found.
     */
    var split: String? = null,

    /**
     * Returns the minSdkVersion from the manifest file. The returned value can be an Integer or a
     * String
     *
     * @return the minSdkVersion or null if value is not set.
     */
    var minSdkVersion: AndroidTarget? = null,

    /**
     * Returns the targetSdkVersion from the manifest file.
     * The returned value can be an Integer or a String
     *
     * @return the targetSdkVersion or null if not found
     */
    var targetSdkVersion: AndroidTarget? = null,

    /**
     * Returns the version name from the manifest file.
     *
     * @return the version name or null if not found.
     */
    var versionName: String? = null,

    /**
     * Returns the version code from the manifest file.
     *
     * @return the version code or null if not found
     */
    var versionCode: Int? = null,

    /**
     * Returns the instrumentation runner from the instrumentation tag in the manifest file.
     *
     * @return the instrumentation runner or `null` if there is none specified.
     */
    var instrumentationRunner: String? = null,

    /**
     * Returns the functionalTest from the instrumentation tag in the manifest file.
     *
     * @return the functionalTest or `null` if there is none specified.
     */
    var functionalTest: Boolean? = null,

    /**
     * Returns the handleProfiling from the instrumentation tag in the manifest file.
     *
     * @return the handleProfiling or `null` if there is none specified.
     */
    var handleProfiling: Boolean? = null,

    /**
     * Returns the testLabel from the instrumentation tag in the manifest file.
     *
     * @return the testLabel or `null` if there is none specified.
     */
    var testLabel: String? = null,

    /**
     * Returns value of the `extractNativeLibs` attribute of the `application` tag, if
     * present.
     */
    var extractNativeLibs: Boolean? = null,

    /**
     * Returns value of the `useEmbeddedDex` attribute of the `application` tag, if
     * present.
     */
    var useEmbeddedDex: Boolean? = null
) {

    /**
     * Temporary holder of api/codename to store the output of the minSdkVersion value.
     * This is not final and will be replaced by the final API class.
     * // FIXME b/150290704
     */
    data class AndroidTarget(
        val apiLevel: Int?,
        val codeName: String?
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ManifestData

        if (packageName != other.packageName) return false
        if (split != other.split) return false
        if (minSdkVersion != other.minSdkVersion) return false
        if (targetSdkVersion != other.targetSdkVersion) return false
        if (versionName != other.versionName) return false
        if (versionCode != other.versionCode) return false
        if (instrumentationRunner != other.instrumentationRunner) return false
        if (functionalTest != other.functionalTest) return false
        if (handleProfiling != other.handleProfiling) return false
        if (testLabel != other.testLabel) return false
        if (extractNativeLibs != other.extractNativeLibs) return false
        if (useEmbeddedDex != other.useEmbeddedDex) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packageName?.hashCode() ?: 0
        result = 31 * result + (split?.hashCode() ?: 0)
        result = 31 * result + (minSdkVersion?.hashCode() ?: 0)
        result = 31 * result + (targetSdkVersion?.hashCode() ?: 0)
        result = 31 * result + (versionName?.hashCode() ?: 0)
        result = 31 * result + (versionCode ?: 0)
        result = 31 * result + (instrumentationRunner?.hashCode() ?: 0)
        result = 31 * result + (functionalTest?.hashCode() ?: 0)
        result = 31 * result + (handleProfiling?.hashCode() ?: 0)
        result = 31 * result + (testLabel?.hashCode() ?: 0)
        result = 31 * result + (extractNativeLibs?.hashCode() ?: 0)
        result = 31 * result + (useEmbeddedDex?.hashCode() ?: 0)
        return result
    }
}

fun parseManifest(
    file: File,
    manifestFileRequired: Boolean,
    manifestParsingAllowed: BooleanSupplier,
    issueReporter: IssueReporter
): ManifestData {
    if (!manifestParsingAllowed.asBoolean) {
        // This is not an exception since we still want sync to succeed if this occurs.
        // Instead print part of the relevant stack trace so that the developer will know
        // how this occurred.
        val stackTrace = Thread.currentThread().stackTrace.map { it.toString() }.filter {
            !it.startsWith("com.tyron.builder.gradle.internal.manifest.") && !it.startsWith("org.gradle.")
        }.subList(1, 10)
        val stackTraceString = stackTrace.joinToString(separator = "\n")
        issueReporter.reportWarning(
            IssueReporter.Type.MANIFEST_PARSED_DURING_CONFIGURATION,
            "The manifest is being parsed during configuration. Please either remove android.disableConfigurationManifestParsing from build.gradle or remove any build configuration rules that read the android manifest file.\n$stackTraceString"
        )
    }

    val data = ManifestData()

    if (!file.exists()) {
        if (manifestFileRequired) {
            issueReporter.reportError(
                IssueReporter.Type.MISSING_ANDROID_MANIFEST,
                "Manifest file does not exist: ${file.absolutePath}"
            )
            // init data with placeholder values to prevent downstream code to have to deal with NPE
            data.packageName = "fake.package.name.for.sync"
        }

        return data
    }

    val handler: DefaultHandler =
        object : DefaultHandler() {
            @Throws(SAXException::class)
            override fun startElement(
                uri: String?,
                localName: String,
                qName: String,
                attributes: Attributes
            ) {
                if (uri.isNullOrEmpty()) {
                    when {
                        SdkConstants.TAG_MANIFEST == localName -> {
                            data.split = attributes.getValue("", SdkConstants.ATTR_SPLIT)
                            data.packageName = attributes.getValue("", SdkConstants.ATTR_PACKAGE)
                            data.versionCode =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_VERSION_CODE
                                )?.toInt()

                            data.versionName =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_VERSION_NAME
                                )

                        }
                        SdkConstants.TAG_INSTRUMENTATION == localName -> {
                            data.testLabel =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_LABEL
                                )

                            data.functionalTest =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_FUNCTIONAL_TEST
                                )?.toBoolean()

                            data.instrumentationRunner = attributes.getValue(
                                SdkConstants.ANDROID_URI,
                                SdkConstants.ATTR_NAME
                            )

                            data.handleProfiling =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_HANDLE_PROFILING
                                )?.toBoolean()
                        }
                        SdkConstants.TAG_USES_SDK == localName -> {

                            data.minSdkVersion =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_MIN_SDK_VERSION
                                )?.toAndroidTarget()

                            data.targetSdkVersion =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_TARGET_SDK_VERSION
                                )?.toAndroidTarget()

                        }
                        SdkConstants.TAG_APPLICATION == localName -> {
                            data.extractNativeLibs =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_EXTRACT_NATIVE_LIBS
                                )?.toBoolean()

                            data.useEmbeddedDex =
                                attributes.getValue(
                                    SdkConstants.ANDROID_URI,
                                    SdkConstants.ATTR_USE_EMBEDDED_DEX
                                )?.toBoolean()
                        }
                    }
                }
            }
        }

    try {
        val newSAXParser = PARSER_FACTORY.newSAXParser()
        val reader = newSAXParser.xmlReader
        // Prevent XML External Entity attack

        // Prevent XML External Entity attack
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        newSAXParser.parse(file, handler)
    } catch (e: Exception) {
        throw RuntimeException(e)
    }
    return data
}

private val PARSER_FACTORY = SAXParserFactory.newInstance().apply {
    try {
        setXIncludeAware(false)
        setNamespaceAware(false) // http://xml.org/sax/features/namespaces
        setFeature("http://xml.org/sax/features/namespace-prefixes", false)
        setFeature("http://xml.org/sax/features/xmlns-uris", false)
        setValidating(false)
    } catch (ignore: ParserConfigurationException) {
    } catch (ignore: SAXException) {
    }
}

private fun String.toAndroidTarget(): ManifestData.AndroidTarget {
    return try {
        val apiLevel = Integer.valueOf(this)

        ManifestData.AndroidTarget(apiLevel = apiLevel, codeName = null)
    } catch (ignored: NumberFormatException) {
        ManifestData.AndroidTarget(apiLevel = null, codeName = this)
    }
}
