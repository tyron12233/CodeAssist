package org.gradle.api.tasks.util.internal;

import org.gradle.api.file.FileTreeElement;
import org.gradle.api.internal.file.RelativePathSpec;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.file.pattern.PatternMatcher;
import org.gradle.internal.file.pattern.PatternMatcherFactory;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.util.Predicates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class PatternSpecFactory {
    public static final PatternSpecFactory INSTANCE = new PatternSpecFactory();
    private String[] previousDefaultExcludes;
    private final Map<CaseSensitivity, Spec<FileTreeElement>>
            defaultExcludeSpecCache = new EnumMap<>(CaseSensitivity.class);

    public Spec<FileTreeElement> createSpec(PatternSet patternSet) {

        return Specs.intersect(createIncludeSpec(patternSet), Specs.negate(createExcludeSpec(patternSet)));
    }

    public Spec<FileTreeElement> createIncludeSpec(PatternSet patternSet) {
        List<Spec<FileTreeElement>> allIncludeSpecs = new ArrayList<>(1 + patternSet.getIncludeSpecs().size());

        if (!patternSet.getIncludes().isEmpty()) {
            allIncludeSpecs.add(createSpec(patternSet.getIncludes(), true, patternSet.isCaseSensitive()));
        }

        allIncludeSpecs.addAll(patternSet.getIncludeSpecs());

        return Specs.union(allIncludeSpecs);
    }

    public Spec<FileTreeElement> createExcludeSpec(PatternSet patternSet) {
        List<Spec<FileTreeElement>> allExcludeSpecs = new ArrayList<>(2 + patternSet.getExcludeSpecs().size());

        if (!patternSet.getExcludes().isEmpty()) {
            allExcludeSpecs.add(createSpec(patternSet.getExcludes(), false, patternSet.isCaseSensitive()));
        }

        allExcludeSpecs.add(getDefaultExcludeSpec(CaseSensitivity.forCaseSensitive(patternSet.isCaseSensitive())));
        allExcludeSpecs.addAll(patternSet.getExcludeSpecs());

        if (allExcludeSpecs.isEmpty()) {
            return Specs.satisfyNone();
        } else {
            return Specs.union(allExcludeSpecs);
        }
    }

    private synchronized Spec<FileTreeElement> getDefaultExcludeSpec(CaseSensitivity caseSensitivity) {
        String[] defaultExcludes = {};
        if (defaultExcludeSpecCache.isEmpty()) {
            updateDefaultExcludeSpecCache(defaultExcludes);
        } else if (invalidChangeOfExcludes(defaultExcludes)) {
            failOnChangedDefaultExcludes(previousDefaultExcludes, defaultExcludes);
        }

        return defaultExcludeSpecCache.get(caseSensitivity);
    }

    private boolean invalidChangeOfExcludes(String[] defaultExcludes) {
        return !Arrays.equals(previousDefaultExcludes, defaultExcludes);
    }

    private void failOnChangedDefaultExcludes(String[] excludesFromSettings, String[] newDefaultExcludes) {
        List<String> sortedExcludesFromSettings = Arrays.asList(excludesFromSettings);
        sortedExcludesFromSettings.sort(Comparator.naturalOrder());
        List<String> sortedNewExcludes = Arrays.asList(newDefaultExcludes);
        sortedNewExcludes.sort(Comparator.naturalOrder());
        throw new IllegalArgumentException(String.format("Cannot change default excludes during the build. They were changed from %s to %s. Configure default excludes in the settings script instead.",  sortedExcludesFromSettings, sortedNewExcludes));
    }

    public synchronized void setDefaultExcludesFromSettings(String[] excludesFromSettings) {
        if (!Arrays.equals(previousDefaultExcludes, excludesFromSettings)) {
            updateDefaultExcludeSpecCache(excludesFromSettings);
        }
    }

    private void updateDefaultExcludeSpecCache(String[] defaultExcludes) {
        previousDefaultExcludes = defaultExcludes;
        List<String> patterns = Arrays.asList(defaultExcludes);
        for (CaseSensitivity caseSensitivity : CaseSensitivity.values()) {
            defaultExcludeSpecCache.put(caseSensitivity, createSpec(patterns, false, caseSensitivity.isCaseSensitive()));
        }
    }

    protected Spec<FileTreeElement> createSpec(Collection<String> patterns, boolean include, boolean caseSensitive) {
        if (patterns.isEmpty()) {
            return include ? Specs.satisfyAll() : Specs.satisfyNone();
        }

        PatternMatcher
                matcher = PatternMatcherFactory.getPatternsMatcher(include, caseSensitive, patterns);

        return new RelativePathSpec(matcher);
    }

    private enum CaseSensitivity {
        CASE_SENSITIVE(true),
        CASE_INSENSITIVE(false);

        CaseSensitivity(boolean caseSensitive) {
            this.caseSensitive = caseSensitive;
        }

        public static CaseSensitivity forCaseSensitive(boolean caseSensitive) {
            return caseSensitive
                    ? CASE_SENSITIVE
                    : CASE_INSENSITIVE;
        }

        private final boolean caseSensitive;

        public boolean isCaseSensitive() {
            return caseSensitive;
        }
    }
}