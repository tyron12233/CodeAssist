package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.UnitOfWork;

import java.time.Duration;

public interface Result {

    /**
     * The elapsed wall clock time of executing the actual work, i.e. the time it took to execute the
     * {@link UnitOfWork#execute(UnitOfWork.ExecutionRequest)} method.
     *
     * The execution time refers to when and where the work was executed: if a previous result was reused,
     * then this method will return the time it took to produce the previous result.
     *
     * Note that reused work times might be different to what it would actually take to execute the work
     * in the current build for a number of reasons:
     *
     * <ul>
     *     <li>reused work could have happened on a remote machine with different hardware capabilities,</li>
     *     <li>there might have been more or less load on the machine producing the reused work,</li>
     *     <li>the work reused might have been executed incrementally,</li>
     *     <li>had there been no work to reuse, the local execution might have happened happen incrementally.</li>
     * </ul>
     */
    Duration getDuration();

    Try<ExecutionResult> getExecutionResult();
}