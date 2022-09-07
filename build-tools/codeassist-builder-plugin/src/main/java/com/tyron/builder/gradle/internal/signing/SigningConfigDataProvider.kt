package com.tyron.builder.gradle.internal.signing

import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.SigningConfigUtils
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.io.Serializable

/**
 * Encapsulates different ways to get the signing config information. It may be `null`.
 *
 * This class is designed to be used by tasks that are interested in the actual signing config
 * information, not the ways to get that information (i.e., *how* to get the info is internal to
 * this class).
 *
 * Those tasks should then annotate this object with `@Nested`, so that if the signing config
 * information has changed, the tasks will be re-executed with the updated info.
 */
class SigningConfigDataProvider(

    /** When not `null`, the signing config information can be obtained directly in memory. */
    @get:Nested
    @get:Optional
    val signingConfigData: Provider<SigningConfigData?>,

    /** When not `null`, the signing config information can be obtained from a file. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    val signingConfigFileCollection: FileCollection?,

    /**
     * The result of validating the signing config information. It may be `null` if the validation
     * is already taken care of elsewhere (e.g., by a different module/task).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    val signingConfigValidationResultDir: Provider<Directory>?
) {

    /** Resolves this provider to get the signing config information. It may be `null`. */
    fun resolve(): SigningConfigData? {
        return convertToParams().resolve()
    }

    /** Converts this provider to [SigningConfigProviderParams] to be used by Gradle workers. */
    fun convertToParams(): SigningConfigProviderParams {
        return SigningConfigProviderParams(
            signingConfigData.orNull,
            signingConfigFileCollection?.let { it.singleFile }
        )
    }

    companion object {

        @JvmStatic
        fun create(creationConfig: ApkCreationConfig): SigningConfigDataProvider {
            val isInDynamicFeature =
                creationConfig.componentType.isDynamicFeature
                        || (creationConfig is TestComponentCreationConfig
                                && creationConfig.mainVariant.componentType.isDynamicFeature)

            // We want to avoid writing the signing config information to disk to protect sensitive
            // data (see bug 137210434), so we'll attempt to get this information directly from
            // memory first.
            return if (!isInDynamicFeature) {
                // Get it from the variant scope
                SigningConfigDataProvider(
                    signingConfigData =
                    // this will resolve all providers of SigningConfig, so we need to
                    // encapsulate in a Provider to avoid these resolutions at configuration
                    // time
                    creationConfig.services.provider {
                        creationConfig.signingConfigImpl?.let {
                            if (it.hasConfig()) {
                                SigningConfigData.fromSigningConfig(creationConfig.signingConfigImpl!!)
                            } else {
                                null
                            }
                        }
                    },
                    signingConfigFileCollection = null,
                    signingConfigValidationResultDir = creationConfig.artifacts.get(
                        InternalArtifactType.VALIDATE_SIGNING_CONFIG
                    )
                )
            } else {
                // Get it from the injected properties passed from the IDE
                val signingConfigData =
                    SigningConfigData.fromProjectOptions(creationConfig.services.projectOptions)

                return if (signingConfigData != null) {
                    SigningConfigDataProvider(
                        signingConfigData = creationConfig.services.provider { signingConfigData },
                        signingConfigFileCollection = null,
                        // Validation for this case is currently missing because the base module
                        // doesn't publish its validation result so that we can use it here.
                        // However, normally the users would build both the base module and the
                        // dynamic feature module, therefore the signing config info for both
                        // modules would be validated when the base module is built, so it may be
                        // acceptable to not validate it here.
                        signingConfigValidationResultDir = null
                    )
                } else {
                    // Otherwise, get it from the published artifact
                    SigningConfigDataProvider(
                        signingConfigData = creationConfig.services.provider { null },
                        signingConfigFileCollection =
                            creationConfig.variantDependencies.getArtifactFileCollection(
                                AndroidArtifacts.ConsumedConfigType.COMPILE_CLASSPATH,
                                AndroidArtifacts.ArtifactScope.PROJECT,
                                AndroidArtifacts.ArtifactType.FEATURE_SIGNING_CONFIG_DATA
                            ),
                        // Validation is taken care of by the task in the base module that publishes
                        // the signing config info (SigningConfigWriterTask).
                        signingConfigValidationResultDir = null
                    )
                }
            }
        }
    }
}

/**
 * Similar to [SigningConfigDataProvider], but uses a [File] instead of a [FileCollection] to be
 * used by Gradle workers.
 */
class SigningConfigProviderParams(
    private val signingConfigData: SigningConfigData?,
    private val signingConfigFile: File?
) : Serializable {

    /** Resolves this provider to get the signing config information. It may be `null`. */
    fun resolve(): SigningConfigData? {
        return signingConfigData
                ?: signingConfigFile?.let { SigningConfigUtils.loadSigningConfigData(it) }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
