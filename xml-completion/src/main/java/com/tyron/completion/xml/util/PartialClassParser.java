package com.tyron.completion.xml.util;

import org.openjdk.com.sun.org.apache.bcel.internal.classfile.ClassFormatException;
import org.openjdk.com.sun.org.apache.bcel.internal.classfile.ClassParser;
import org.openjdk.com.sun.org.apache.bcel.internal.classfile.JavaClass;

import java.io.IOException;
import java.io.InputStream;

/**
 * Parses class files using bcel with the needed information only to determine its type.
 */
public class PartialClassParser extends ClassParser {

    public PartialClassParser(InputStream file, String file_name) {
        super(file, file_name);
    }

    public PartialClassParser(String file_name) throws IOException {
        super(file_name);
    }

    public PartialClassParser(String zip_file, String file_name) throws IOException {
        super(zip_file, file_name);
    }

    @Override
    public JavaClass parse() throws IOException, ClassFormatException {
        readID();
        readVersion();
        readConstantPool();
        readClassInfo();

        file.close();
        if (zip != null) {
            zip.close();
        }

        return new JavaClass(class_name_index, superclass_name_index,
                             file_name, major, minor, access_flags,
                             constant_pool, interfaces, fields,
                             methods, attributes, is_zip? JavaClass.ZIP : JavaClass.FILE);
    }
}
