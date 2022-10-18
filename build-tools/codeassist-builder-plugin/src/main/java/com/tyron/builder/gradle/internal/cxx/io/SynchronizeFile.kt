package com.tyron.builder.gradle.internal.cxx.io

class SynchronizeFile {

    enum class Comparison(i: Int) {
        UNKNOWN_COMPARISON(0),
        NOT_SAME_SOURCE_DID_NOT_EXIST(1),
        NOT_SAME_DESTINATION_DID_NOT_EXIST(2),
        NOT_SAME_LENGTH(3),
        NOT_SAME_CONTENT(4), // Expensive check

        SAME_SOURCE_AND_DESTINATION_DID_NOT_EXIST(100),
        SAME_PATH_BY_FILE_OBJECT_IDENTITY(101),
        SAME_PATH_ACCORDING_TO_LEXICAL_PATH(102),
        SAME_PATH_ACCORDING_TO_FILE_SYSTEM_PROVIDER(103),
        SAME_PATH_ACCORDING_TO_CANONICAL_PATH(104),
        SAME_CONTENT(105); // Expensive check
    }
}
