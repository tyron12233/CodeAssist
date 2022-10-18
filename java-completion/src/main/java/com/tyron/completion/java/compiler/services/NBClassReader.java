package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.code.ClassFinder.BadClassFile;

import java.util.Set;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.jvm.ClassFile;
import com.sun.tools.javac.jvm.ClassFile.Version;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.resources.CompilerProperties.Warnings;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileObject;

/**
 * @author lahvac
 */
public class NBClassReader extends ClassReader {

    public static void preRegister(Context context) {
        context.put(classReaderKey, (Context.Factory<ClassReader>) NBClassReader::new);
    }

    private final Names names;
    private final NBNames nbNames;
    private final Log log;

    public NBClassReader(Context context) {
        super(context);

        names = Names.instance(context);
        nbNames = NBNames.instance(context);
        log = Log.instance(context);

        NBAttributeReader[] readers =
                {new NBAttributeReader(nbNames._org_netbeans_EnclosingMethod, Version.V45_3,
                        CLASS_OR_MEMBER_ATTRIBUTE) {
                    public void read(Symbol sym, int attrLen) {
                        int newbp = bp + attrLen;
                        readEnclosingMethodAttr(sym);
                        bp = newbp;
                    }
                },};

        for (NBAttributeReader r : readers)
            attributeReaders.put(r.getName(), r);
    }

    @Override
    public void readClassFile(ClassSymbol c) {
        try {
            super.readClassFile(c);
        } catch (BadClassFile cf) {
            if ("compiler.misc.bad.class.file.header".equals(cf.getDiagnostic().getCode())) {
                JavaFileObject origFile = c.classfile;
                try (InputStream in = origFile.openInputStream()) {
                    byte[] data = readFile(in);
                    int major = (Byte.toUnsignedInt(data[6]) << 8) + Byte.toUnsignedInt(data[7]);
                    int maxMajor = ClassFile.Version.MAX().major;
                    if (maxMajor < major) {
                        if (log.currentSourceFile() != null) {
                            log.warning(0, Warnings.BigMajorVersion(origFile, major, maxMajor));
                        }
                        data[6] = (byte) (maxMajor >> 8);
                        data[7] = (byte) (maxMajor & 0xFF);
                        c.classfile = new ForwardingJavaFileObject<JavaFileObject>(origFile) {
                            @Override
                            public InputStream openInputStream() {
                                return new ByteArrayInputStream(data);
                            }
                        };
                        super.readClassFile(c);
                        return;
                    }
                } catch (IOException ex) {
                    Logger.getLogger(NBClassReader.class.getName()).log(Level.FINE, null, ex);
                } finally {
                    c.classfile = origFile;
                }
            }
            throw cf;
        }
    }

    static byte[] readFile(final InputStream in) throws IOException {
        byte[] data = new byte[Math.max(in.available(), 256)];
        int off = 0;
        int read;
        while ((read = in.read(data, off, data.length - off)) != (-1)) {
            off += read;
            if (data.length == off) {
                data = Arrays.copyOf(data, 2 * (data.length + in.available()));
            }
        }
        return Arrays.copyOf(data, off);
    }

    private abstract class NBAttributeReader extends AttributeReader {

        private NBAttributeReader(Name name, Version version, Set<AttributeKind> kinds) {
            super(name, version, kinds);
        }

        private Name getName() {
            return name;
        }
    }

}