package com.tyron.builder.api.internal.exceptions;
import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats candidates as a list.
 */
public class FormattingDiagnosticsVisitor implements DiagnosticsVisitor {
    private final Map<String, Candidate> candidates = new LinkedHashMap<String, Candidate>();
    private Candidate current;

    public List<String> getCandidates() {
        return format(candidates);
    }

    private List<String> format(Map<String, Candidate> candidates) {
        List<String> formatted = new ArrayList<String>();
        for (Candidate candidate : candidates.values()) {
            if (candidate.examples.isEmpty()) {
                formatted.add(candidate.description);
            } else {
                formatted.add(String.format("%s, for example %s.", candidate.description, Joiner
                        .on(", ").join(candidate.examples)));
            }
        }
        return formatted;
    }

    @Override
    public DiagnosticsVisitor candidate(String displayName) {
        Candidate candidate = candidates.get(displayName);
        if (candidate == null) {
            candidate = new Candidate(displayName);
            candidates.put(displayName, candidate);
        }
        current = candidate;
        return this;
    }

    @Override
    public DiagnosticsVisitor example(String example) {
        current.examples.add(example);
        return this;
    }

    @Override
    public DiagnosticsVisitor values(Iterable<?> values) {
        return this;
    }

    private static class Candidate {
        final String description;
        final List<String> examples = new ArrayList<String>();

        public Candidate(String description) {
            this.description = description;
        }
    }
}