package com.tyron.completion;

import me.xdrop.fuzzywuzzy.FuzzySearch;

/**
 * Logic of matching a completion name with a given completion prefix
 */
public class CompletionPrefixMatcher {

    /**
     * The minimum score needed for a candidate to be considered as partial match.
     */
    private static final int MINIMUM_SCORE = 70;

    /**
     * How well does the candidate name match the completion prefix.
     *
     * <p>THe ordinal values of the enum values imply the match level. The greater the ordinal value
     * is the better the candidate matches. It can be a safe key for sorting the matched candidates.
     * New value should be added to the right place to keep the ordinal value in order.</p>
     */
    public enum MatchLevel {
        NOT_MATCH,
        PARTIAL_MATCH,
        CASE_INSENSITIVE_PREFIX,
        CASE_SENSITIVE_PREFIX,
        CASE_INSENSITIVE_EQUAL,
        CASE_SENSITIVE_EQUAL
    }

    public static MatchLevel computeMatchLevel(String candidateName, String completionPrefix) {
        if (candidateName.startsWith(completionPrefix)) {
            return candidateName.length() == completionPrefix.length()
                    ? MatchLevel.CASE_SENSITIVE_EQUAL
                    : MatchLevel.CASE_SENSITIVE_PREFIX;
        }

        if (candidateName.toLowerCase().startsWith(completionPrefix.toLowerCase())) {
            return candidateName.length() == completionPrefix.length()
                    ? MatchLevel.CASE_INSENSITIVE_EQUAL
                    : MatchLevel.CASE_INSENSITIVE_PREFIX;
        }

        int score = FuzzySearch.ratio(candidateName, completionPrefix);
        if (score > MINIMUM_SCORE) {
            return MatchLevel.PARTIAL_MATCH;
        }
        return MatchLevel.NOT_MATCH;
    }
}
