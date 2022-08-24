package org.gradle.tooling.internal.protocol;

/**
 * DO NOT CHANGE THIS INTERFACE. It is part of the cross-version protocol.
 *
 * Describes an unexpected test failure, i.e. when the execution fails but not with an assertion failure.
 *
 * @since 7.6
 */
public interface InternalTestFrameworkFailure extends InternalTestFailure {

}