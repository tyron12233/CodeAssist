package org.gradle.internal.execution.steps;

/**
 * Context necessary for steps that change the outputs.
 *
 * This context doesn't add any new information, it encodes a requirement
 * in the type system that a step can change the outputs.
 */
public interface ChangingOutputsContext extends InputChangesContext {
}