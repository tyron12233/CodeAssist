package com.tyron.builder.model.v2.ide

/**
 * Information to identify a sub-project dependency.
 */
interface ProjectInfo: ComponentInfo {

    /**
     * The build id.
     */
    val buildId: String

    /**
     * The project path.
     */
    val projectPath: String
}
