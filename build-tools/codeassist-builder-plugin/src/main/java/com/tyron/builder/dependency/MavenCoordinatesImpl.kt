package com.tyron.builder.dependency

import com.android.SdkConstants
import com.android.annotations.concurrency.Immutable
import com.google.common.base.Objects
import com.tyron.builder.internal.StringCachingService
import com.tyron.builder.internal.cacheString
import com.tyron.builder.model.MavenCoordinates
import java.io.Serializable

/**
 * Serializable implementation of MavenCoordinates for use in the model.
 */
@Immutable
class MavenCoordinatesImpl private constructor(
    override val groupId: String,
    override val artifactId: String,
    override val version: String,
    override val packaging: String,
    override val classifier: String?,
    override val versionlessId: String,
    private val toString: String
) : MavenCoordinates, Serializable {

    companion object {
        private const val serialVersionUID = 1L

        @JvmStatic
        @JvmOverloads
        fun create(
            stringCachingService: StringCachingService?,
            groupId: String,
            artifactId: String,
            version: String,
            packaging: String? = null,
            classifier: String? = null
        ): MavenCoordinatesImpl {
            val packagingStr = if (packaging == null) {
                SdkConstants.EXT_JAR
            } else {
                stringCachingService.cacheString(packaging)
            }

            return MavenCoordinatesImpl(
                stringCachingService.cacheString(groupId),
                stringCachingService.cacheString(artifactId),
                stringCachingService.cacheString(version),
                packagingStr,
                classifier?.let { stringCachingService.cacheString(it) },
                stringCachingService.cacheString(
                    computeVersionLessId(
                        groupId,
                        artifactId,
                        classifier
                    )
                ),
                stringCachingService.cacheString(
                    computeToString(
                        groupId, artifactId, version, packagingStr, classifier
                    )
                )
            )
        }

        private fun computeVersionLessId(
            groupId: String,
            artifactId: String,
            classifier: String?
        ): String {
            val sb = StringBuilder(
                groupId.length
                        + groupId.length
                        + artifactId.length
                        + 2
                        + (classifier?.let { it.length + 1} ?: 0)
            )

            sb.append(groupId).append(':').append(artifactId)
            classifier?.let {
                sb.append(':').append(it)
            }

            return sb.toString()
        }

        private fun computeToString(
            groupId: String,
            artifactId: String,
            version: String,
            packaging: String,
            classifier: String?
        ): String {
            val sb = StringBuilder(
                groupId.length
                        + artifactId.length
                        + version.length
                        + 2 // the 2 ':'
                        + (if (classifier != null) classifier.length + 1 else 0) // +1 for the ':'
                        + packaging.length
                        + 1 // +1 for the '@'
            )
            sb.append(groupId).append(':').append(artifactId).append(':').append(version)
            classifier?.let {
                sb.append(':').append(it)
            }
            sb.append('@').append(packaging)
            return sb.toString()
        }
    }

    // pre-computed derived values for performance, not part of the object identity.
    private val hashCode: Int = computeHashCode()

    fun compareWithoutVersion(coordinates: MavenCoordinates): Boolean {
        return this === coordinates ||
                Objects.equal(groupId, coordinates.groupId) &&
                Objects.equal(
                    artifactId,
                    coordinates.artifactId
                ) &&
                Objects.equal(
                    packaging,
                    coordinates.packaging
                ) &&
                Objects.equal(
                    classifier,
                    coordinates.classifier
                )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val that = other as MavenCoordinatesImpl
        return Objects.equal(groupId, that.groupId) &&
                Objects.equal(artifactId, that.artifactId) &&
                Objects.equal(version, that.version) &&
                Objects.equal(packaging, that.packaging) &&
                Objects.equal(classifier, that.classifier)
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun toString(): String {
        return toString
    }

    private fun computeHashCode(): Int {
        return Objects.hashCode(groupId, artifactId, version, packaging, classifier)
    }
}
