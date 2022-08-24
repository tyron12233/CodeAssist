package org.gradle.tooling.internal.provider;

import org.gradle.tooling.internal.protocol.PhasedActionResult;
import org.gradle.tooling.internal.provider.serialization.SerializedPayload;

import java.io.Serializable;

/**
 * Result of one of the actions of a phased action. Must be serializable since will be dispatched to client.
 */
public class PhasedBuildActionResult implements Serializable {
    public final SerializedPayload result;
    public final PhasedActionResult.Phase phase;

    public PhasedBuildActionResult(SerializedPayload result, PhasedActionResult.Phase phase) {
        this.result = result;
        this.phase = phase;
    }
}