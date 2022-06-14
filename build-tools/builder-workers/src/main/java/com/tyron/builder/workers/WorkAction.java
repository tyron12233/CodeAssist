package com.tyron.builder.workers;

import javax.inject.Inject;

/**
 * Represents the implementation of a unit of work to be used when submitting work to the
 * {@link WorkerExecutor}.
 *
 * <p>
 *     A work action implementation is an abstract class implementing the {@link #execute()} method.
 *     A minimal implementation may look like this:
 * </p>
 *
 * <pre class='autoTested'>
 * import org.gradle.workers.WorkParameters;
 *
 * public abstract class MyWorkAction implements WorkAction&lt;WorkParameters.None&gt; {
 *     private final String greeting;
 *
 *     {@literal @}Inject
 *     public MyWorkAction() {
 *         this.greeting = "hello";
 *     }
 *
 *     {@literal @}Override
 *     public void execute() {
 *         System.out.println(greeting);
 *     }
 * }
 * </pre>
 *
 * Implementations of WorkAction are subject to the following constraints:
 * <ul>
 *     <li>Do not implement {@link #getParameters()} in your class, the method will be implemented by Gradle.</li>
 *     <li>Constructors must be annotated with {@link Inject}.</li>
 * </ul>
 *
 * @param <T> Parameter type for the work action. Should be {@link WorkParameters.None} if the action does not have parameters.
 * @since 5.6
 **/
public interface WorkAction<T extends WorkParameters> {
    /**
     * The parameters associated with a concrete work item.
     */
    @Inject
    T getParameters();

    /**
     * The work to perform when this work item executes.
     */
    void execute();
}
