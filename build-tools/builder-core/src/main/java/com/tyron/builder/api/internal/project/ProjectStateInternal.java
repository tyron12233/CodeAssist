package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.ProjectConfigurationException;
import com.tyron.builder.api.ProjectState;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the lifecycle state of a project, with regard to configuration.
 *
 * There are three synonymous terms mixed in here (configure, evaluate, execute) for legacy reasons.
 * Where not bound to backwards compatibility constraints, we use the term “configure”.
 *
 * @see LifecycleProjectEvaluator
 */
@SuppressWarnings("JavadocReference")
public class ProjectStateInternal implements ProjectState {

    enum State {
        UNCONFIGURED,
        IN_BEFORE_EVALUATE,
        IN_EVALUATE,
        IN_AFTER_EVALUATE,
        CONFIGURED
    }

    private State state = State.UNCONFIGURED;
    private ProjectConfigurationException failure;

    @Override
    public boolean getExecuted() {
        // We intentionally consider “execution” done before doing afterEvaluate.
        // The Android plugin relies on this behaviour.
        return state.ordinal() > State.IN_EVALUATE.ordinal();
    }

    public boolean isConfiguring() {
        // Intentionally asymmetrical to getExecuted()
        // This prevents recursion on `project.afterEvaluate { project.evaluate() }`
        return state == State.IN_BEFORE_EVALUATE || state == State.IN_EVALUATE || state == State.IN_AFTER_EVALUATE;
    }

    public boolean isUnconfigured() {
        return state == State.UNCONFIGURED;
    }

    public boolean hasCompleted() {
        return state == State.CONFIGURED;
    }

    public void toBeforeEvaluate() {
        assert state == State.UNCONFIGURED;
        state = State.IN_BEFORE_EVALUATE;
    }

    public void toEvaluate() {
        assert state == State.IN_BEFORE_EVALUATE;
        state = State.IN_EVALUATE;
    }

    public void toAfterEvaluate() {
        assert state == State.IN_EVALUATE;
        state = State.IN_AFTER_EVALUATE;
    }

    public void configured() {
        assert state != State.CONFIGURED;
        state = State.CONFIGURED;
    }

    public void failed(ProjectConfigurationException failure) {
        if (this.failure == null) {
            this.failure = failure;
        } else {
            List<Throwable> causes = new ArrayList<Throwable>(this.failure.getCauses());
            causes.addAll(failure.getCauses());
            this.failure.initCauses(causes);
        }
    }

    public boolean hasFailure() {
        return failure != null;
    }

    @Override
    public Throwable getFailure() {
        return failure;
    }

    @Override
    public void rethrowFailure() {
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public String toString() {
        String state;

        if (isConfiguring()) {
            state = "EXECUTING";
        } else if (getExecuted()) {
            if (failure == null) {
                state = "EXECUTED";
            } else {
                state = String.format("FAILED (%s)", failure.getMessage());
            }
        } else {
            state = "NOT EXECUTED";
        }

        return String.format("project state '%s'", state);
    }
}