package com.tyron.builder.gradle.internal.dsl

import java.lang.RuntimeException

class AgpDslLockedException(cause: String) : RuntimeException(cause) {
}