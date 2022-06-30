package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Attribute.RetentionPolicy;
import com.sun.tools.javac.code.Attribute.TypeCompound;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.jvm.Target;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import java.util.Collection;

/**
 *
 * @author lahvac
 */
public class NBClassWriter extends ClassWriter {

    public static void preRegister(Context context) {
        context.put(classWriterKey, (Context.Factory<ClassWriter>) NBClassWriter::new);
    }

    private final NBNames nbNames;
    private final Types types;

    protected NBClassWriter(Context context) {
        super(context);
        nbNames = NBNames.instance(context);
        types = Types.instance(context);
    }
    
}