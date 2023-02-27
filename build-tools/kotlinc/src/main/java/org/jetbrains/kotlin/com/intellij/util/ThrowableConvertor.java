package org.jetbrains.kotlin.com.intellij.util;

/**
 * Not a 'converter for Throwables', but ThrowAbleConverter: converter (U->V) which is able to
 * throw exception T
 */
@FunctionalInterface
public interface ThrowableConvertor<U, V, T extends Throwable> {
  V convert(U u) throws T;
}