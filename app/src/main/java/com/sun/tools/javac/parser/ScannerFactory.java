/*
 * Copyright (c) 1999, 2010, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.parser;

import com.sun.tools.javac.code.Source;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

import java.nio.CharBuffer;


/**
 * A factory for creating scanners.
 * <p>
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b>
 */
public class ScannerFactory {
    /**
     * The context key for the scanner factory.
     */
    public static final Context.Key<ScannerFactory> scannerFactoryKey =
            new Context.Key<ScannerFactory>();
    public final Log log;
    public final Names names;

    public final Source source;
    public final Keywords keywords;

    /**
     * Create a new scanner factory.
     */
    protected ScannerFactory(Context context) {
        context.put(scannerFactoryKey, this);
        this.log = Log.instance(context);
        this.names = Names.instance(context);
        this.source = Source.instance(context);
        this.keywords = Keywords.instance(context);
    }

    /**
     * Get the Factory instance for this context.
     */
    public static ScannerFactory instance(Context context) {
        ScannerFactory instance = context.get(scannerFactoryKey);
        if (instance == null)
            instance = new ScannerFactory(context);
        return instance;
    }

    public Scanner newScanner(CharSequence input, boolean keepDocComments) {
        if (input instanceof CharBuffer) {
            CharBuffer buf = (CharBuffer) input;
            if (keepDocComments)
                return new DocCommentScanner(this, buf);
            else
                return new Scanner(this, buf);
        } else {
            char[] array = input.toString().toCharArray();
            return newScanner(array, array.length, keepDocComments);
        }
    }

    public Scanner newScanner(char[] input, int inputLength, boolean keepDocComments) {
        if (keepDocComments)
            return new DocCommentScanner(this, input, inputLength);
        else
            return new Scanner(this, input, inputLength);
    }
}
