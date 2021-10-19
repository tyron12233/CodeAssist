package com.tyron.lookup;

import com.tyron.completions.CompletionContributor;
import com.tyron.util.ClassConditionKey;

import org.jetbrains.annotations.NotNull;

/**
 * What to do if there's only one element in completion lookup? Should the IDE show lookup or just insert this element? Call
 * {@link #applyPolicy(LookupElement)} to decorate {@link LookupElement} with correct policy.
 * <p>
 * Use this only in simple cases, use {@link CompletionContributor#handleAutoCompletionPossibility(com.intellij.codeInsight.completion.AutoCompletionContext)}
 * for finer tuning.
 *
 * @author peter
 */
public enum AutoCompletionPolicy {
    /**
     * Self-explaining.
     */
    NEVER_AUTOCOMPLETE,

    /**
     * If 'auto-complete if only one choice' is configured in settings, the item will be inserted, otherwise - no.
     */
    SETTINGS_DEPENDENT,

    /**
     * If caret is positioned inside an identifier, and 'auto-complete if only one choice' is configured in settings,
     * a lookup with one item will still open, giving user a chance to overwrite the identifier using Tab key
     */
    GIVE_CHANCE_TO_OVERWRITE,

    /**
     * Self-explaining.
     */
    ALWAYS_AUTOCOMPLETE;

    @NotNull
    public LookupElement applyPolicy(@NotNull LookupElement element) {
        return new PolicyDecorator(element, this);
    }

    private static class PolicyDecorator extends LookupElementDecorator<LookupElement> {
        public static final ClassConditionKey<PolicyDecorator> CLASS_CONDITION_KEY = ClassConditionKey.create(PolicyDecorator.class);
        private final AutoCompletionPolicy myPolicy;

        PolicyDecorator(LookupElement element, AutoCompletionPolicy policy) {
            super(element);
            myPolicy = policy;
        }

        @Override
        public AutoCompletionPolicy getAutoCompletionPolicy() {
            return myPolicy;
        }
    }
}
