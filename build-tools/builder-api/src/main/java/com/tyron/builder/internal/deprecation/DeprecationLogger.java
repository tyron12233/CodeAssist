package com.tyron.builder.internal.deprecation;

import org.graalvm.compiler.debug.DebugContext;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Provides entry points for constructing and emitting deprecation messages.
 * The basic deprecation message structure is "Summary. DeprecationTimeline. Context. Advice. Documentation."
 *
 * The deprecateX methods in this class return a builder that guides creation of the deprecation message.
 * Summary is populated by the deprecateX methods in this class.
 * Context can be added in free text using {@link DeprecationMessageBuilder#withContext(String)}.
 * Advice is constructed contextually using {@link DeprecationMessageBuilder.WithReplacement#replaceWith(Object)} methods based on the thing being deprecated. Alternatively, it can be populated using {@link DeprecationMessageBuilder#withAdvice(String)}.
 * DeprecationTimeline is mandatory and is added using one of:
 * - ${@link DeprecationMessageBuilder#willBeRemovedInGradle8()}
 * - ${@link DeprecationMessageBuilder#willBecomeAnErrorInGradle8()}
 * After DeprecationTimeline is set, Documentation reference must be added using one of:
 * - {@link DeprecationMessageBuilder.WithDeprecationTimeline#withUpgradeGuideSection(int, String)}
 * - {@link DeprecationMessageBuilder.WithDeprecationTimeline#withDslReference(Class, String)}
 * - {@link DeprecationMessageBuilder.WithDeprecationTimeline#withUserManual(String, String)}
 *
 * In order for the deprecation message to be emitted, terminal operation {@link DeprecationMessageBuilder.WithDocumentation#nagUser()} has to be called after one of the documentation providing methods.
 */
@ThreadSafe
public class DeprecationLogger {

    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return true;
        }
    };

//    /**
//     * Output: ${behaviour}.
//     */
//    @CheckReturnValue
//    public static DeprecationMessageBuilder.DeprecateBehaviour deprecateBehaviour(String behaviour) {
//        return new DeprecationMessageBuilder.DeprecateBehaviour(behaviour);
//    }

//    private static final LoggingDeprecatedFeatureHandler DEPRECATED_FEATURE_HANDLER = new LoggingDeprecatedFeatureHandler();
}
