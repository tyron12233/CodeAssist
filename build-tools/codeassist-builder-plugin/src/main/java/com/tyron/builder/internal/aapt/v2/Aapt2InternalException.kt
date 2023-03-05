package com.tyron.builder.internal.aapt.v2

/** Exception thrown when there is an issue with the AAPT2 infrastructure.  */
class Aapt2InternalException(description: String, cause: Throwable) :
        RuntimeException(description, cause)