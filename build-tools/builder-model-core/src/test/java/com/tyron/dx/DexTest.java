package com.tyron.dx;

import com.android.dex.DexFormat;
import com.android.dx.Code;
import com.android.dx.Comparison;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Label;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import com.android.dx.dex.DexOptions;
import com.android.dx.dex.file.ClassDefItem;
import com.android.dx.dex.file.DexFile;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

public class DexTest {
    @Test
    public void test() throws Exception {
        DexMaker dexMaker = new DexMaker();

        TypeId<Object> aClass = TypeId.get("LGeneratedClass;");
        dexMaker.declare(aClass, "source", Modifier.PUBLIC, TypeId.OBJECT);

        FieldId<Object, String> string_field = aClass.getField(TypeId.STRING, "STRING_FIELD");
        dexMaker.declare(string_field, Modifier.STATIC, "TEST");

        MethodId<Object, Void> main = aClass.getMethod(TypeId.VOID, "main");
        Code declare = dexMaker.declare(main, Modifier.PUBLIC | Modifier.STATIC);

        Local<String> stringLocal = declare.newLocal(TypeId.STRING);
        Local<String> nullLocal = declare.newLocal(TypeId.STRING);
        Local<PrintStream> printStreamLocal = declare.newLocal(TypeId.get(PrintStream.class));
        declare.sget(string_field, stringLocal);

        TypeId<System> systemTypeId = TypeId.get(System.class);
        FieldId<System, PrintStream> outField =
                systemTypeId.getField(TypeId.get(PrintStream.class), "out");
        MethodId<PrintStream, Void> println =
                TypeId.get(PrintStream.class).getMethod(TypeId.VOID, "println", TypeId.STRING);

        declare.sget(outField, printStreamLocal);

        Label label = new Label();
        declare.compareZ(Comparison.EQ, label, stringLocal);
        declare.invokeVirtual(println, null, printStreamLocal, nullLocal);
        declare.returnVoid();


        declare.mark(label);
        declare.returnVoid();


        DexOptions options = new DexOptions();
        options.minSdkVersion = DexFormat.API_NO_EXTENDED_OPCODES;
        DexFile outputDex = new DexFile(options);

        Field types = DexMaker.class.getDeclaredField("types");
        types.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) types.get(dexMaker);
        for (Object value : map.values()) {
            Method toClassDefItem = value.getClass().getDeclaredMethod("toClassDefItem");
            toClassDefItem.setAccessible(true);
            Object invoke = toClassDefItem.invoke(value);
            outputDex.add((ClassDefItem) invoke);
        }

        File tempFile = File.createTempFile("tempFile", "jar");
        outputDex.writeTo(new FileOutputStream(tempFile), new PrintWriter(System.out), true);

        JadxArgs args = new JadxArgs();
        args.setUseDxInput(true);
        args.setInputFile(tempFile);

        JadxDecompiler decompiler = new JadxDecompiler(args);
        decompiler.load();

        JavaClass javaClass = decompiler.getClasses().get(0);
        javaClass.decompile();
        System.out.println(javaClass.getCode());
    }
}
