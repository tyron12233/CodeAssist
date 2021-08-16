/*
 * Copyright (c) 2003, 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.code;

import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Constants;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Pair;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import javax.lang.model.type.DeclaredType;

import static com.sun.tools.javac.code.TypeTags.BOOLEAN;
import static com.sun.tools.javac.code.TypeTags.BYTE;
import static com.sun.tools.javac.code.TypeTags.CHAR;
import static com.sun.tools.javac.code.TypeTags.DOUBLE;
import static com.sun.tools.javac.code.TypeTags.FLOAT;
import static com.sun.tools.javac.code.TypeTags.INT;
import static com.sun.tools.javac.code.TypeTags.LONG;
import static com.sun.tools.javac.code.TypeTags.SHORT;

/** An annotation value.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public abstract class Attribute implements AnnotationValue {

    /** The type of the annotation element. */
    public Type type;

    public Attribute(Type type) {
        this.type = type;
    }

    public abstract void accept(Visitor v);

    public Object getValue() {
        throw new UnsupportedOperationException();
    }

    public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
        throw new UnsupportedOperationException();
    }


    /** The value for an annotation element of primitive type or String. */
    public static class Constant extends Attribute {
        public final Object value;
        public void accept(Visitor v) { v.visitConstant(this); }
        public Constant(Type type, Object value) {
            super(type);
            this.value = value;
        }
        public String toString() {
            return Constants.format(value, type);
        }
        public Object getValue() {
            return Constants.decode(value, type);
        }
        public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
            if (value instanceof String)
                return v.visitString((String) value, p);
            if (value instanceof Integer) {
                int i = (Integer) value;
                switch (type.tag) {
                case BOOLEAN:   return v.visitBoolean(i != 0, p);
                case CHAR:      return v.visitChar((char) i, p);
                case BYTE:      return v.visitByte((byte) i, p);
                case SHORT:     return v.visitShort((short) i, p);
                case INT:       return v.visitInt(i, p);
                }
            }
            switch (type.tag) {
            case LONG:          return v.visitLong((Long) value, p);
            case FLOAT:         return v.visitFloat((Float) value, p);
            case DOUBLE:        return v.visitDouble((Double) value, p);
            }
            throw new AssertionError("Bad annotation element value: " + value);
        }
    }

    /** The value for an annotation element of type java.lang.Class,
     *  represented as a ClassSymbol.
     */
    public static class Class extends Attribute {
        public final Type type;
        public void accept(Visitor v) { v.visitClass(this); }
        public Class(Types types, Type type) {
            super(makeClassType(types, type));
            this.type = type;
        }
        static Type makeClassType(Types types, Type type) {
            Type arg = type.isPrimitive()
                ? types.boxedClass(type).type
                : types.erasure(type);
            return new Type.ClassType(types.syms.classType.getEnclosingType(),
                                      List.of(arg),
                                      types.syms.classType.tsym);
        }
        public String toString() {
            return type + ".class";
        }
        public Type getValue() {
            return type;
        }
        public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
            return v.visitType(type, p);
        }
    }

    /** A compound annotation element value, the type of which is an
     *  attribute interface.
     */
    public static class Compound extends Attribute implements AnnotationMirror {
        /** The attributes values, as pairs.  Each pair contains a
         *  reference to the accessing method in the attribute interface
         *  and the value to be returned when that method is called to
         *  access this attribute.
         */
        public final List<Pair<MethodSymbol,Attribute>> values;
        public Compound(Type type,
                        List<Pair<MethodSymbol,Attribute>> values) {
            super(type);
            this.values = values;
        }
        public void accept(Visitor v) { v.visitCompound(this); }

        /**
         * Returns a string representation of this annotation.
         * String is of one of the forms:
         *     @com.example.foo(name1=val1, name2=val2)
         *     @com.example.foo(val)
         *     @com.example.foo
         * Omit parens for marker annotations, and omit "value=" when allowed.
         */
        public String toString() {
            StringBuilder buf = new StringBuilder();
            buf.append("@");
            buf.append(type);
            int len = values.length();
            if (len > 0) {
                buf.append('(');
                boolean first = true;
                for (Pair<MethodSymbol, Attribute> value : values) {
                    if (!first) buf.append(", ");
                    first = false;

                    Name name = value.fst.name;
                    if (len > 1 || name != name.table.names.value) {
                        buf.append(name);
                        buf.append('=');
                    }
                    buf.append(value.snd);
                }
                buf.append(')');
            }
            return buf.toString();
        }

        public Attribute member(Name member) {
            for (Pair<MethodSymbol,Attribute> pair : values)
                if (pair.fst.name == member) return pair.snd;
            return null;
        }

        public Compound getValue() {
            return this;
        }

        public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
            return v.visitAnnotation(this, p);
        }

        public DeclaredType getAnnotationType() {
            return (DeclaredType) type;
        }

        public Map<MethodSymbol, Attribute> getElementValues() {
            Map<MethodSymbol, Attribute> valmap =
                new LinkedHashMap<MethodSymbol, Attribute>();
            for (Pair<MethodSymbol, Attribute> value : values)
                valmap.put(value.fst, value.snd);
            return valmap;
        }
    }

    /** The value for an annotation element of an array type.
     */
    public static class Array extends Attribute {
        public final Attribute[] values;
        public Array(Type type, Attribute[] values) {
            super(type);
            this.values = values;
        }
        public void accept(Visitor v) { v.visitArray(this); }
        public String toString() {
            StringBuilder buf = new StringBuilder();
            buf.append('{');
            boolean first = true;
            for (Attribute value : values) {
                if (!first)
                    buf.append(", ");
                first = false;
                buf.append(value);
            }
            buf.append('}');
            return buf.toString();
        }
        public List<Attribute> getValue() {
            return List.from(values);
        }
        public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
            return v.visitArray(getValue(), p);
        }
    }

    /** The value for an annotation element of an enum type.
     */
    public static class Enum extends Attribute {
        public VarSymbol value;
        public Enum(Type type, VarSymbol value) {
            super(type);
            this.value = Assert.checkNonNull(value);
        }
        public void accept(Visitor v) { v.visitEnum(this); }
        public String toString() {
            return value.enclClass() + "." + value;     // qualified name
        }
        public VarSymbol getValue() {
            return value;
        }
        public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
            return v.visitEnumConstant(value, p);
        }
    }

    public static class Error extends Attribute {
        public Error(Type type) {
            super(type);
        }
        public void accept(Visitor v) { v.visitError(this); }
        public String toString() {
            return "<error>";
        }
        public String getValue() {
            return toString();
        }
        public <R, P> R accept(AnnotationValueVisitor<R, P> v, P p) {
            return v.visitString(toString(), p);
        }
    }

    /** A visitor type for dynamic dispatch on the kind of attribute value. */
    public static interface Visitor {
        void visitConstant(Constant value);
        void visitClass(Class clazz);
        void visitCompound(Compound compound);
        void visitArray(Array array);
        void visitEnum(Enum e);
        void visitError(Error e);
    }

    /** A mirror of java.lang.annotation.RetentionPolicy. */
    public static enum RetentionPolicy {
        SOURCE,
        CLASS,
        RUNTIME
    }
}
