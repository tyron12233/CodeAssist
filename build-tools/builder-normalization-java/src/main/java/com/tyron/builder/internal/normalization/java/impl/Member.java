package com.tyron.builder.internal.normalization.java.impl;


import com.google.common.collect.ComparisonChain;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Models a single element of a codebase that may be inspected and acted upon with
 * bytecode manipulation libraries tools like ASM.
 *
 * <p>The notion of "member" here is similar to, but broader than
 * {@link java.lang.reflect.Member}. The latter is essentially an abstraction over fields,
 * methods and constructors; this Member and its subtypes represent not only fields and
 * methods, but also classes, inner classes, annotations and their values, and more. This
 * model is minimalistic and has a few assumptions about being used in an ASM context, but
 * provides us in any case with what we need to effectively find and manipulate API
 * members and construct API classes out of them.
 */
public abstract class Member {

    private final String name;

    public Member(String name) {
        this.name = checkNotNull(name);
    }

    public String getName() {
        return name;
    }

    protected ComparisonChain compare(Member o) {
        return ComparisonChain.start().compare(name, o.name);
    }
}