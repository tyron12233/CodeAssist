/*
 * Copyright (c) 2003, 2005, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package com.sun.tools.javac.jvm;

import com.sun.tools.javac.code.*;


/**
 * These pseudo-types appear in the generated verifier tables to
 * indicate objects that have been allocated but not yet constructed.
 * <p>
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
class UninitializedType extends Type.DelegatedType {
    public static final int UNINITIALIZED_THIS = TypeTags.TypeTagCount;
    public static final int UNINITIALIZED_OBJECT = UNINITIALIZED_THIS + 1;

    public static UninitializedType uninitializedThis(Type qtype) {
        return new UninitializedType(UNINITIALIZED_THIS, qtype, -1);
    }

    public static UninitializedType uninitializedObject(Type qtype, int offset) {
        return new UninitializedType(UNINITIALIZED_OBJECT, qtype, offset);
    }

    public final int offset; // PC where allocation took place

    private UninitializedType(int tag, Type qtype, int offset) {
        super(tag, qtype);
        this.offset = offset;
    }

    Type initializedType() {
        return qtype;
    }
}
