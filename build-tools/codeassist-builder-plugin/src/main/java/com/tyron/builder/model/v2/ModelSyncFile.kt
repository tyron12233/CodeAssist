package com.tyron.builder.model.v2

import java.io.File

/**
 * Represents a model sync file.
 *
 * A model sync file is produced by the Android Gradle Plugin during the execution phase and
 * contains information that cannot be determined at configuration time.
 */
interface ModelSyncFile {
    /**
     * Enum of all sync types supported by this plugin.
     */
    enum class ModelSyncType {

        /**
         * Sync that will contain elements of type com.android.ide.model.sync.AppIdListSync
         */
        APP_ID_LIST,

        /**
         * Basic sync type will contain elements of type com.android.ide.model.sync.Variant.
         *
         */
        BASIC,
    }

    /**
     * [ModelSyncType] for this file.
     */
    val modelSyncType: ModelSyncType

    /**
     * Name of the task that can produce the model sync file. The task must have executed
     * successfully for the [syncFile] file to be available.
     */
    val taskName: String

    /**
     * Sync file currently in the proto format.
     *
     * The content depends on the [modelSyncType]
     */
    val syncFile: File
}
