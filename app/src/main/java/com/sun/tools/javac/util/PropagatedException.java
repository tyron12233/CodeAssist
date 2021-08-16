/*
 * Copyright (c) 2006, Oracle and/or its affiliates. All rights reserved.
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
 * Used to propagate exceptions through to the user.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b></p>
 *
 * @author Peter von der Ah\u00e9
 */
public class PropagatedException extends RuntimeException {

    static final long serialVersionUID = -6065309339888775367L;

    public PropagatedException(RuntimeException cause) {
        super(cause);
    }

    @Override
    public RuntimeException getCause() {
        return (RuntimeException)super.getCause();
    }
}
