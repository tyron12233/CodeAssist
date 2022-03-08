package com.tyron.completion.xml.util;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PartialClassParser {

    private DataInputStream dataInputStream;
    private final boolean fileOwned;
    private final String fileName;
    private String zipFile;
    private int classNameIndex;
    private int superclassNameIndex;
    private int major; // Compiler version
    private int minor; // Compiler version
    private int accessFlags; // Access rights of parsed class
    private int[] interfaces; // Names of implemented interfaces
    private ConstantPool constantPool; // collection of constants
    private Field[] fields; // class fields, i.e., its variables
    private Method[] methods; // methods defined in the class
    private Attribute[] attributes; // attributes defined in the class
    private final boolean isZip; // Loaded from zip file
    private static final int BUFSIZE = 8192;


    /**
     * Parses class from the given stream.
     *
     * @param inputStream Input stream
     * @param fileName File name
     */
    public PartialClassParser(final InputStream inputStream, final String fileName) {
        this.fileName = fileName;
        fileOwned = false;
        final String clazz = inputStream.getClass().getName(); // Not a very clean solution ...
        isZip = clazz.startsWith("java.util.zip.") || clazz.startsWith("java.util.jar.");
        if (inputStream instanceof DataInputStream) {
            this.dataInputStream = (DataInputStream) inputStream;
        } else {
            this.dataInputStream = new DataInputStream(new BufferedInputStream(inputStream, BUFSIZE));
        }
    }


    /** Parses class from given .class file.
     *
     * @param fileName file name
     */
    public PartialClassParser(final String fileName) {
        isZip = false;
        this.fileName = fileName;
        fileOwned = true;
    }


    /** Parses class from given .class file in a ZIP-archive
     *
     * @param zipFile zip file name
     * @param fileName file name
     */
    public PartialClassParser(final String zipFile, final String fileName) {
        isZip = true;
        fileOwned = true;
        this.zipFile = zipFile;
        this.fileName = fileName;
    }


    /**
     * Parses the given Java class file and return an object that represents
     * the contained data, i.e., constants, methods, fields and commands.
     * A <em>ClassFormatException</em> is raised, if the file is not a valid
     * .class file. (This does not include verification of the byte code as it
     * is performed by the java interpreter).
     *
     * @return Class object representing the parsed class file
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    public org.apache.bcel.classfile.JavaClass parse() throws IOException, org.apache.bcel.classfile.ClassFormatException {
        ZipFile zip = null;
        try {
            if (fileOwned) {
                if (isZip) {
                    zip = new ZipFile(zipFile);
                    final ZipEntry entry = zip.getEntry(fileName);

                    if (entry == null) {
                        throw new IOException("File " + fileName + " not found");
                    }

                    dataInputStream = new DataInputStream(new BufferedInputStream(zip.getInputStream(entry),
                                                                                  BUFSIZE));
                } else {
                    dataInputStream = new DataInputStream(new BufferedInputStream(new FileInputStream(
                            fileName), BUFSIZE));
                }
            }
            /****************** Read headers ********************************/
            // Check magic tag of class file
            readID();
            // Get compiler version
            readVersion();
            /****************** Read constant pool and related **************/
            // Read constant pool entries
            readConstantPool();
            // Get class information
            readClassInfo();
            // Get interface information, i.e., implemented interfaces
//            readInterfaces();
//            /****************** Read class fields and methods ***************/
//            // Read class fields, i.e., the variables of the class
//            readFields();
//            // Read class methods, i.e., the functions in the class
//            readMethods();
//            // Read class attributes
//            readAttributes();
            // Check for unknown variables
            //Unknown[] u = Unknown.getUnknownAttributes();
            //for (int i=0; i < u.length; i++)
            //  System.err.println("WARNING: " + u[i]);
            // Everything should have been read now
            //      if(file.available() > 0) {
            //        int bytes = file.available();
            //        byte[] buf = new byte[bytes];
            //        file.read(buf);
            //        if(!(isZip && (buf.length == 1))) {
            //      System.err.println("WARNING: Trailing garbage at end of " + fileName);
            //      System.err.println(bytes + " extra bytes: " + Utility.toHexString(buf));
            //        }
            //      }
        } finally {
            // Read everything of interest, so close the file
            if (fileOwned) {
                try {
                    if (dataInputStream != null) {
                        dataInputStream.close();
                    }
                } catch (final IOException ioe) {
                    //ignore close exceptions
                }
            }
            try {
                if (zip != null) {
                    zip.close();
                }
            } catch (final IOException ioe) {
                //ignore close exceptions
            }
        }
        // Return the information we have gathered in a new object
        return new org.apache.bcel.classfile.JavaClass(classNameIndex, superclassNameIndex, fileName, major, minor,
                                                       accessFlags, constantPool, interfaces, fields, methods, attributes, isZip
                                                               ? org.apache.bcel.classfile.JavaClass.ZIP
                                                               : JavaClass.FILE);
    }


    /**
     * Reads information about the attributes of the class.
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    private void readAttributes() throws IOException, org.apache.bcel.classfile.ClassFormatException {
        final int attributes_count = dataInputStream.readUnsignedShort();
        attributes = new Attribute[attributes_count];
        for (int i = 0; i < attributes_count; i++) {
            attributes[i] = Attribute.readAttribute(dataInputStream, constantPool);
        }
    }


    /**
     * Reads information about the class and its super class.
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    private void readClassInfo() throws IOException, org.apache.bcel.classfile.ClassFormatException {
        accessFlags = dataInputStream.readUnsignedShort();
        /* Interfaces are implicitely abstract, the flag should be set
         * according to the JVM specification.
         */
        if ((accessFlags & Const.ACC_INTERFACE) != 0) {
            accessFlags |= Const.ACC_ABSTRACT;
        }
        if (((accessFlags & Const.ACC_ABSTRACT) != 0)
            && ((accessFlags & Const.ACC_FINAL) != 0)) {
            throw new org.apache.bcel.classfile.ClassFormatException("Class " + fileName + " can't be both final and abstract");
        }
        classNameIndex = dataInputStream.readUnsignedShort();
        superclassNameIndex = dataInputStream.readUnsignedShort();
    }


    /**
     * Reads constant pool entries.
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    private void readConstantPool() throws IOException, org.apache.bcel.classfile.ClassFormatException {
        constantPool = new ConstantPool(dataInputStream);
    }


    /******************** Private utility methods **********************/
    /**
     * Checks whether the header of the file is ok.
     * Of course, this has to be the first action on successive file reads.
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    private void readID() throws IOException, org.apache.bcel.classfile.ClassFormatException {
        if (dataInputStream.readInt() != Const.JVM_CLASSFILE_MAGIC) {
            throw new org.apache.bcel.classfile.ClassFormatException(fileName + " is not a Java .class file");
        }
    }


    /**
     * Reads information about the interfaces implemented by this class.
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    private void readInterfaces() throws IOException, org.apache.bcel.classfile.ClassFormatException {
        final int interfaces_count = dataInputStream.readUnsignedShort();
        interfaces = new int[interfaces_count];
        for (int i = 0; i < interfaces_count; i++) {
            interfaces[i] = dataInputStream.readUnsignedShort();
        }
    }



    /**
     * Reads major and minor version of compiler which created the file.
     * @throws  IOException
     * @throws org.apache.bcel.classfile.ClassFormatException
     */
    private void readVersion() throws IOException, ClassFormatException {
        minor = dataInputStream.readUnsignedShort();
        major = dataInputStream.readUnsignedShort();
    }
}
