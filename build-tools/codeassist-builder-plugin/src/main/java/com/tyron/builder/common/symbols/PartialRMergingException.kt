package com.tyron.builder.common.symbols

/** Exception thrown when an error occurs during partial R files merging.  */
class PartialRMergingException : Exception {

    constructor(description: String) : super(description)

    constructor(description: String, cause: Throwable) : super(description, cause)
}