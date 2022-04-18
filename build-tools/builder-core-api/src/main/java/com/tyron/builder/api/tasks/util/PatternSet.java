package com.tyron.builder.api.tasks.util;

import com.google.common.collect.Sets;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.typeconversion.NotationParser;
import com.tyron.builder.api.internal.typeconversion.NotationParserBuilder;
import com.tyron.builder.api.tasks.util.internal.PatternSpecFactory;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

public class PatternSet implements PatternFilterable {

    private static final NotationParser<Object, String>
            PARSER = NotationParserBuilder.toType(String.class).fromCharSequence().toComposite();
    private final PatternSpecFactory patternSpecFactory;

    private Set<String> includes;
    private Set<String> excludes;
    private Set<Predicate<FileTreeElement>> includeSpecs;
    private Set<Predicate<FileTreeElement>> excludeSpecs;
    private boolean caseSensitive = true;

    public PatternSet() {
        this(PatternSpecFactory.INSTANCE);
    }

    protected PatternSet(PatternSet patternSet) {
        this(patternSet.patternSpecFactory);
    }

    protected PatternSet(PatternSpecFactory patternSpecFactory) {
        this.patternSpecFactory = patternSpecFactory;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PatternSet)) {
            return false;
        }

        PatternSet that = (PatternSet) o;

        if (caseSensitive != that.caseSensitive) {
            return false;
        }
        if (!nullToEmpty(excludeSpecs).equals(nullToEmpty(that.excludeSpecs))) {
            return false;
        }
        if (!nullToEmpty(excludes).equals(nullToEmpty(that.excludes))) {
            return false;
        }
        if (!nullToEmpty(includeSpecs).equals(nullToEmpty(that.includeSpecs))) {
            return false;
        }
        if (!nullToEmpty(includes).equals(nullToEmpty(that.includes))) {
            return false;
        }

        return true;
    }

    private Set<?> nullToEmpty(@Nullable Set<?> set) {
        if (set == null) {
            return Collections.emptySet();
        }
        return set;
    }

    public PatternSet copyFrom(PatternFilterable sourcePattern) {
        return doCopyFrom((PatternSet) sourcePattern);
    }

    protected PatternSet doCopyFrom(PatternSet from) {
        caseSensitive = from.caseSensitive;

        if (from instanceof IntersectionPatternSet) {
            PatternSet other = ((IntersectionPatternSet) from).getOther();
            PatternSet otherCopy = new PatternSet(other).copyFrom(other);
            PatternSet intersectCopy = new IntersectionPatternSet(otherCopy);
            copyIncludesAndExcludes(intersectCopy, from);

            includes = null;
            excludes = null;
            includeSpecs = Sets.newLinkedHashSet();
            includeSpecs.add(intersectCopy.getAsSpec());
            excludeSpecs = null;
        } else {
            copyIncludesAndExcludes(this, from);
        }

        return this;
    }

    private void copyIncludesAndExcludes(PatternSet target, PatternSet from) {
        target.includes = from.includes == null ? null : Sets.newLinkedHashSet(from.includes);
        target.excludes = from.excludes == null ? null : Sets.newLinkedHashSet(from.excludes);
        target.includeSpecs = from.includeSpecs == null ? null : Sets.newLinkedHashSet(from.includeSpecs);
        target.excludeSpecs = from.excludeSpecs == null ? null : Sets.newLinkedHashSet(from.excludeSpecs);
    }

    public PatternSet intersect() {
        if (isEmpty()) {
            return new PatternSet(this.patternSpecFactory);
        } else {
            return new IntersectionPatternSet(this);
        }
    }

    /**
     * The PatternSet is considered empty when no includes or excludes have been added.
     *
     * The Spec returned by getAsSpec method only contains the default excludes patterns
     * in this case.
     *
     * @return true when no includes or excludes have been added to this instance
     */
    public boolean isEmpty() {
        return (includes == null || includes.isEmpty())
               && (excludes == null || excludes.isEmpty())
               && (includeSpecs == null || includeSpecs.isEmpty())
               && (excludeSpecs == null || excludeSpecs.isEmpty());
    }

    public Predicate<FileTreeElement> getAsSpec() {
        return patternSpecFactory.createSpec(this);
    }

    public Predicate<FileTreeElement> getAsIncludeSpec() {
        return patternSpecFactory.createIncludeSpec(this);
    }

    public Predicate<FileTreeElement> getAsExcludeSpec() {
        return patternSpecFactory.createExcludeSpec(this);
    }

    public Set<String> getIncludes() {
        if (includes == null) {
            includes = Sets.newLinkedHashSet();
        }
        return includes;
    }

    public Set<Predicate<FileTreeElement>> getIncludeSpecs() {
        if (includeSpecs == null) {
            includeSpecs = Sets.newLinkedHashSet();
        }
        return includeSpecs;
    }

    public PatternSet setIncludes(Iterable<String> includes) {
        this.includes = null;
        return include(includes);
    }


    public PatternSet include(String... includes) {
        Collections.addAll(getIncludes(), includes);
        return this;
    }

    public PatternSet include(Iterable includes) {
        for (Object include : includes) {
            getIncludes().add(PARSER.parseNotation(include));
        }
        return this;
    }

    public PatternSet include(Predicate<FileTreeElement> spec) {
        getIncludeSpecs().add(spec);
        return this;
    }

    public Set<String> getExcludes() {
        if (excludes == null) {
            excludes = Sets.newLinkedHashSet();
        }
        return excludes;
    }


    public Set<Predicate<FileTreeElement>> getExcludeSpecs() {
        if (excludeSpecs == null) {
            excludeSpecs = Sets.newLinkedHashSet();
        }
        return excludeSpecs;
    }

    public PatternSet setExcludes(Iterable<String> excludes) {
        this.excludes = null;
        return exclude(excludes);
    }


    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    /*
    This can't be called just include, because it has the same erasure as include(Iterable<String>).
     */
    public PatternSet includeSpecs(Iterable<Predicate<FileTreeElement>> includeSpecs) {
        getIncludeSpecs().addAll((Collection<? extends Predicate<FileTreeElement>>) includeSpecs);
        return this;
    }

    public PatternSet exclude(String... excludes) {
        Collections.addAll(getExcludes(), excludes);
        return this;
    }

    public PatternSet exclude(Iterable excludes) {
        for (Object exclude : excludes) {
            getExcludes().add(PARSER.parseNotation(exclude));
        }
        return this;
    }

    public PatternSet exclude(Predicate<FileTreeElement> spec) {
        getExcludeSpecs().add(spec);
        return this;
    }

    public PatternSet excludeSpecs(Iterable<Predicate<FileTreeElement>> excludes) {
        getExcludeSpecs().addAll((Collection<? extends Predicate<FileTreeElement>>) excludes);
        return this;
    }

}
