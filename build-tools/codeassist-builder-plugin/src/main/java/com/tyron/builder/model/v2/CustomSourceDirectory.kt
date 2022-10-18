package com.tyron.builder.model.v2

import java.io.File

interface CustomSourceDirectory {
    /**
     * Source name as represented by the user. It must be unique for the project.
     */
    val sourceTypeName: String

    /**
     * the single source folder for the source type.
     */
    val directory: File
}