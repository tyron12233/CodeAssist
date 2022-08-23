package com.tyron.builder.api.artifact.impl

/**
 * Defines common behaviors of operation requests on an [com.android.build.api.artifact.Artifact]
 */
interface ArtifactOperationRequest {

    /**
     * Closes the operation request, all input parameters have been provided and the operation has
     * enough information to proceed to the next step.
     */
    fun closeRequest() {
        artifacts.closeRequest(this)
    }

    val description: String

    val artifacts: ArtifactsImpl
}