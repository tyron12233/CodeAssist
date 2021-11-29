/*
 * reserved comment block
 * DO NOT REMOVE OR ALTER!
 */
/*
 * Copyright 2001-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * $Id: IntType.java,v 1.2.4.1 2005/09/05 11:14:44 pvedula Exp $
 */

package org.openjdk.com.sun.org.apache.xalan.internal.xsltc.compiler.util;

import org.openjdk.com.sun.org.apache.bcel.internal.generic.BranchHandle;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.BranchInstruction;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.CHECKCAST;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.ConstantPoolGen;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.GOTO;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IFEQ;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IFGE;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IFGT;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IFLE;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IFLT;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IF_ICMPGE;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IF_ICMPGT;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IF_ICMPLE;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.IF_ICMPLT;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.ILOAD;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.INVOKESPECIAL;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.INVOKESTATIC;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.INVOKEVIRTUAL;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.ISTORE;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.Instruction;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.InstructionConstants;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.InstructionList;
import org.openjdk.com.sun.org.apache.bcel.internal.generic.NEW;
import org.openjdk.com.sun.org.apache.xalan.internal.xsltc.compiler.Constants;
import org.openjdk.com.sun.org.apache.xalan.internal.xsltc.compiler.FlowList;

/**
 * @author Jacek Ambroziak
 * @author Santiago Pericas-Geertsen
 */
public final class IntType extends NumberType {
    protected IntType() {}

    public String toString() {
        return "int";
    }

    public boolean identicalTo(Type other) {
        return this == other;
    }

    public String toSignature() {
        return "I";
    }

    public org.openjdk.com.sun.org.apache.bcel.internal.generic.Type toJCType() {
        return org.openjdk.com.sun.org.apache.bcel.internal.generic.Type.INT;
    }

    /**
     * @see     Type#distanceTo
     */
    public int distanceTo(Type type) {
        if (type == this) {
            return 0;
        }
        else if (type == Real) {
            return 1;
        }
        else
            return Integer.MAX_VALUE;
    }

    /**
     * Translates an integer into an object of internal type <code>type</code>.
     *
     * @see     Type#translateTo
     */
    public void translateTo(ClassGenerator classGen, MethodGenerator methodGen,
                            final Type type) {
        if (type == Real) {
            translateTo(classGen, methodGen, (RealType) type);
        }
        else if (type == String) {
            translateTo(classGen, methodGen, (StringType) type);
        }
        else if (type == Boolean) {
            translateTo(classGen, methodGen, (BooleanType) type);
        }
        else if (type == Reference) {
            translateTo(classGen, methodGen, (ReferenceType) type);
        }
        else {
            ErrorMsg err = new ErrorMsg(ErrorMsg.DATA_CONVERSION_ERR,
                                        toString(), type.toString());
            classGen.getParser().reportError(Constants.FATAL, err);
        }
    }

    /**
     * Expects an integer on the stack and pushes a real.
     *
     * @see     Type#translateTo
     */
    public void translateTo(ClassGenerator classGen, MethodGenerator methodGen,
                            RealType type) {
        methodGen.getInstructionList().append(I2D);
    }

    /**
     * Expects an integer on the stack and pushes its string value by calling
     * <code>Integer.toString(int i)</code>.
     *
     * @see     Type#translateTo
     */
    public void translateTo(ClassGenerator classGen, MethodGenerator methodGen,
                            StringType type) {
        final ConstantPoolGen cpg = classGen.getConstantPool();
        final InstructionList il = methodGen.getInstructionList();
        il.append(new INVOKESTATIC(cpg.addMethodref(INTEGER_CLASS,
                                                    "toString",
                                                    "(I)" + STRING_SIG)));
    }

    /**
     * Expects an integer on the stack and pushes a 0 if its value is 0 and
     * a 1 otherwise.
     *
     * @see     Type#translateTo
     */
    public void translateTo(ClassGenerator classGen, MethodGenerator methodGen,
                            BooleanType type) {
        final InstructionList il = methodGen.getInstructionList();
        final BranchHandle falsec = il.append(new IFEQ(null));
        il.append(ICONST_1);
        final BranchHandle truec = il.append(new GOTO(null));
        falsec.setTarget(il.append(ICONST_0));
        truec.setTarget(il.append(NOP));
    }

    /**
     * Expects an integer on the stack and translates it to a non-synthesized
     * boolean. It does not push a 0 or a 1 but instead returns branchhandle
     * list to be appended to the false list.
     *
     * @see     Type#translateToDesynthesized
     */
    public FlowList translateToDesynthesized(ClassGenerator classGen,
                                             MethodGenerator methodGen,
                                             BooleanType type) {
        final InstructionList il = methodGen.getInstructionList();
        return new FlowList(il.append(new IFEQ(null)));
    }

    /**
     * Expects an integer on the stack and pushes a boxed integer.
     * Boxed integers are represented by an instance of
     * <code>java.lang.Integer</code>.
     *
     * @see     Type#translateTo
     */
    public void translateTo(ClassGenerator classGen, MethodGenerator methodGen,
                            ReferenceType type) {
        final ConstantPoolGen cpg = classGen.getConstantPool();
        final InstructionList il = methodGen.getInstructionList();
        il.append(new NEW(cpg.addClass(INTEGER_CLASS)));
        il.append(DUP_X1);
        il.append(SWAP);
        il.append(new INVOKESPECIAL(cpg.addMethodref(INTEGER_CLASS,
                                                     "<init>", "(I)V")));
    }

    /**
     * Translates an integer into the Java type denoted by <code>clazz</code>.
     * Expects an integer on the stack and pushes a number of the appropriate
     * type after coercion.
     */
    public void translateTo(ClassGenerator classGen, MethodGenerator methodGen,
                            Class clazz) {
        final InstructionList il = methodGen.getInstructionList();
        if (clazz == Character.TYPE) {
            il.append(I2C);
        }
        else if (clazz == Byte.TYPE) {
            il.append(I2B);
        }
        else if (clazz == Short.TYPE) {
            il.append(I2S);
        }
        else if (clazz == Integer.TYPE) {
            il.append(NOP);
        }
        else if (clazz == Long.TYPE) {
            il.append(I2L);
        }
        else if (clazz == Float.TYPE) {
            il.append(I2F);
        }
        else if (clazz == Double.TYPE) {
            il.append(I2D);
        }
         // Is Double <: clazz? I.e. clazz in { Double, Number, Object }
       else if (clazz.isAssignableFrom(java.lang.Double.class)) {
           il.append(I2D);
           Real.translateTo(classGen, methodGen, Reference);
        }
        else {
            ErrorMsg err = new ErrorMsg(ErrorMsg.DATA_CONVERSION_ERR,
                                        toString(), clazz.getName());
            classGen.getParser().reportError(Constants.FATAL, err);
        }
    }

    /**
     * Translates an object of this type to its boxed representation.
     */
    public void translateBox(ClassGenerator classGen,
                             MethodGenerator methodGen) {
        translateTo(classGen, methodGen, Reference);
    }

    /**
     * Translates an object of this type to its unboxed representation.
     */
    public void translateUnBox(ClassGenerator classGen,
                               MethodGenerator methodGen) {
        final ConstantPoolGen cpg = classGen.getConstantPool();
        final InstructionList il = methodGen.getInstructionList();
        il.append(new CHECKCAST(cpg.addClass(INTEGER_CLASS)));
        final int index = cpg.addMethodref(INTEGER_CLASS,
                                           INT_VALUE,
                                           INT_VALUE_SIG);
        il.append(new INVOKEVIRTUAL(index));
    }

    public Instruction ADD() {
        return InstructionConstants.IADD;
    }

    public Instruction SUB() {
        return InstructionConstants.ISUB;
    }

    public Instruction MUL() {
        return InstructionConstants.IMUL;
    }

    public Instruction DIV() {
        return InstructionConstants.IDIV;
    }

    public Instruction REM() {
        return InstructionConstants.IREM;
    }

    public Instruction NEG() {
        return InstructionConstants.INEG;
    }

    public Instruction LOAD(int slot) {
        return new ILOAD(slot);
    }

    public Instruction STORE(int slot) {
        return new ISTORE(slot);
    }

    public BranchInstruction GT(boolean tozero) {
        return tozero ? (BranchInstruction) new IFGT(null) :
            (BranchInstruction) new IF_ICMPGT(null);
    }

    public BranchInstruction GE(boolean tozero) {
        return tozero ? (BranchInstruction) new IFGE(null) :
            (BranchInstruction) new IF_ICMPGE(null);
    }

    public BranchInstruction LT(boolean tozero) {
        return tozero ? (BranchInstruction) new IFLT(null) :
            (BranchInstruction) new IF_ICMPLT(null);
    }

    public BranchInstruction LE(boolean tozero) {
        return tozero ? (BranchInstruction) new IFLE(null) :
            (BranchInstruction) new IF_ICMPLE(null);
    }
}
