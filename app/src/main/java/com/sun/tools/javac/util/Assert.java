/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.util;


/**
 * Simple facility for unconditional assertions.
 * The methods in this class are described in terms of equivalent assert
 * statements, assuming that assertions have been enabled.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Assert {
    /** Equivalent to
     *   assert cond;
     */
    public static void check(boolean cond) {
        if (!cond)
            error();
    }

    /** Equivalent to
     *   assert (o == null);
     */
    public static void checkNull(Object o) {
        if (o != null)
            error();
    }

    /** Equivalent to
     *   assert (t != null); return t;
     */
    public static <T> T checkNonNull(T t) {
        if (t == null)
            error();
        return t;
    }

    /** Equivalent to
     *   assert cond : value;
     */
    public static void check(boolean cond, int value) {
        if (!cond)
            error(String.valueOf(value));
    }

    /** Equivalent to
     *   assert cond : value;
     */
    public static void check(boolean cond, long value) {
        if (!cond)
            error(String.valueOf(value));
    }

    /** Equivalent to
     *   assert cond : value;
     */
    public static void check(boolean cond, Object value) {
        if (!cond)
            error(String.valueOf(value));
    }

    /** Equivalent to
     *   assert cond : value;
     */
    public static void check(boolean cond, String msg) {
        if (!cond)
            error(msg);
    }

    /** Equivalent to
     *   assert (o == null) : value;
     */
    public static void checkNull(Object o, Object value) {
        if (o != null)
            error(String.valueOf(value));
    }

    /** Equivalent to
     *   assert (o == null) : value;
     */
    public static void checkNull(Object o, String msg) {
        if (o != null)
            error(msg);
    }

    /** Equivalent to
     *   assert (o != null) : value;
     */
    public static <T> T checkNonNull(T t, String msg) {
        if (t == null)
            error(msg);
        return t;
    }

    /** Equivalent to
     *   assert false;
     */
    public static void error() {
        throw new AssertionError();
    }

    /** Equivalent to
     *   assert false : msg;
     */
    public static void error(String msg) {
        throw new AssertionError(msg);
    }

    /** Prevent instantiation. */
    private Assert() { }
}
