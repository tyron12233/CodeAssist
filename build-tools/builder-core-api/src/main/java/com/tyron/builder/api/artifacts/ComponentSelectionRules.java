package com.tyron.builder.api.artifacts;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.internal.HasInternalProtocol;

/***
 * Represents a container for component selection rules.  Rules can be applied as part of the
 * resolutionStrategy of a configuration and individual components can be explicitly accepted
 * or rejected by rule.  Components that are neither accepted or rejected will be subject to
 * the default version matching strategies.
 *
 * <pre class='autoTested'>
 *     configurations {
 *         conf {
 *             resolutionStrategy {
 *                 componentSelection {
 *                     all { ComponentSelection selection -&gt;
 *                         if (selection.candidate.module == 'someModule' &amp;&amp; selection.candidate.version == '1.1') {
 *                             selection.reject("bad version '1.1' for 'someModule'")
 *                         }
 *                     }
 *                     all { ComponentSelection selection -&gt;
 *                         if (selection.candidate.module == 'someModule' &amp;&amp; selection.getDescriptor(IvyModuleDescriptor)?.branch == 'testing') {
 *                             if (selection.metadata == null || selection.metadata.status != 'milestone') {
 *                                 selection.reject("only use milestones for someModule:testing")
 *                             }
 *                         }
 *                     }
 *                     withModule("org.sample:api") { ComponentSelection selection -&gt;
 *                         if (selection.candidate.version == "1.1") {
 *                             selection.reject("known bad version")
 *                         }
 *                     }
 *                 }
 *             }
 *         }
 *     }
 * </pre>
 */
@HasInternalProtocol
public interface ComponentSelectionRules {
    /**
     * Adds a simple component selection rule that will apply to all resolved components.
     * Each rule will receive a {@link ComponentSelection} object as an argument.
     *
     * @param selectionAction the Action that implements a rule to be applied
     * @return this
     */
    ComponentSelectionRules all(Action<? super ComponentSelection> selectionAction);

    /**
     * Adds a component selection rule that will apply to all resolved components.
     *
     * Each rule will receive a {@link ComponentSelection} object as an argument.
     *
     * @param closure the Closure that implements a rule to be applied
     * @return this
     */
    ComponentSelectionRules all(Closure<?> closure);

    /**
     * Adds a rule-source backed component selection rule that will apply to all resolved components.
     *
     * The ruleSource provides the rule as exactly one rule method annotated with {@link com.tyron.builder.model.Mutate}.
     *
     * This rule method:
     * <ul>
     *     <li>must return void.</li>
     *     <li>must have {@link com.tyron.builder.api.artifacts.ComponentSelection} as its parameter.</li>
     * </ul>
     *
     * @param ruleSource an instance providing a rule implementation
     * @return this
     */
    ComponentSelectionRules all(Object ruleSource);

    /**
     * Adds a component selection rule that will apply to the specified module.
     * Each rule will receive a {@link ComponentSelection} object as an argument.
     *
     * @param id the module to apply this rule to in "group:module" format or as a {@link com.tyron.builder.api.artifacts.ModuleIdentifier}
     * @param selectionAction the Action that implements a rule to be applied
     * @return this
     */
    ComponentSelectionRules withModule(Object id, Action<? super ComponentSelection> selectionAction);

    /**
     * Adds a component selection rule that will apply to the specified module.
     *
     * Each rule will receive a {@link ComponentSelection} object as an argument.
     *
     * @param id the module to apply this rule to in "group:module" format or as a {@link com.tyron.builder.api.artifacts.ModuleIdentifier}
     * @param closure the Closure that implements a rule to be applied
     * @return this
     */
    ComponentSelectionRules withModule(Object id, Closure<?> closure);

    /**
     * Adds a rule-source backed component selection rule that will apply to the specified module.
     *
     * The ruleSource provides the rule as exactly one rule method annotated with {@link com.tyron.builder.model.Mutate}.
     *
     * This rule method:
     * <ul>
     *     <li>must return void.</li>
     *     <li>must have {@link com.tyron.builder.api.artifacts.ComponentSelection} as its parameter.</li>
     * </ul>
     *
     * @param id the module to apply this rule to in "group:module" format or as a {@link com.tyron.builder.api.artifacts.ModuleIdentifier}
     * @param ruleSource an instance providing a rule implementation
     * @return this
     */
    ComponentSelectionRules withModule(Object id, Object ruleSource);
}
