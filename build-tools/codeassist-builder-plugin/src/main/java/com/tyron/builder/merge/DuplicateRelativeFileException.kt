package com.tyron.builder.merge

/**
 * Exception by [StreamMergeAlgorithms.acceptOnlyOne] if more than one file needs to be
 * merged.
 */
class DuplicateRelativeFileException constructor(
    private val path: String,
    private val size: Int,
    private val inputs: List<String>,
    cause : DuplicateRelativeFileException?
) : RuntimeException(cause) {

    override val message: String
        get() {
            return StringBuilder().apply {
                append(size).append(" files found with path '").append(path).append("'")
                if (inputs.isEmpty()) {
                    append(".\n")
                } else {
                    append(" from inputs:\n")
                    for (input in inputs) {
                        append(" - ").append(input).append("\n")
                    }
                }
                if (path.endsWith(".so")) {
                    append(
                        "If you are using jniLibs and CMake IMPORTED targets, see\n" +
                                "https://developer.android.com/r/tools/jniLibs-vs-imported-targets"
                    )
                } else {
                    append(
                        "Adding a packagingOptions block may help, please refer to\n" +
                                "${("com/android/build/api/dsl/ResourcesPackagingOptions")}\n" +
                                "for more information"
                    )
                }
            }.toString()
        }

    constructor(path: String, size: Int) : this(path, size, listOf(), null)

    constructor(
        inputs: List<IncrementalFileMergerInput>,
        cause: DuplicateRelativeFileException
    ) : this(cause.path, cause.size, inputs.map { it.name }, cause)

}
