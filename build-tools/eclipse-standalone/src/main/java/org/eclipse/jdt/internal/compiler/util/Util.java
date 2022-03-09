/*******************************************************************************
 * Copyright (c) 2000, 2019 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     daolaf@gmail.com - Contribution for bug 3292227
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.eclipse.jdt.internal.compiler.batch.Main;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TagBits;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;

/**
 * CodeAssist changed: used alternatives for InputStream#readAllBytes since android does not
 * support it
 */
public class Util implements SuffixConstants {

    /**
     * Character constant indicating the primitive type boolean in a signature.
     * Value is <code>'Z'</code>.
     */
    public static final char C_BOOLEAN 		= 'Z';

    /**
     * Character constant indicating the primitive type byte in a signature.
     * Value is <code>'B'</code>.
     */
    public static final char C_BYTE 		= 'B';

    /**
     * Character constant indicating the primitive type char in a signature.
     * Value is <code>'C'</code>.
     */
    public static final char C_CHAR 		= 'C';

    /**
     * Character constant indicating the primitive type double in a signature.
     * Value is <code>'D'</code>.
     */
    public static final char C_DOUBLE 		= 'D';

    /**
     * Character constant indicating the primitive type float in a signature.
     * Value is <code>'F'</code>.
     */
    public static final char C_FLOAT 		= 'F';

    /**
     * Character constant indicating the primitive type int in a signature.
     * Value is <code>'I'</code>.
     */
    public static final char C_INT 			= 'I';

    /**
     * Character constant indicating the semicolon in a signature.
     * Value is <code>';'</code>.
     */
    public static final char C_SEMICOLON 			= ';';

    /**
     * Character constant indicating the colon in a signature.
     * Value is <code>':'</code>.
     * @since 3.0
     */
    public static final char C_COLON 			= ':';

    /**
     * Character constant indicating the primitive type long in a signature.
     * Value is <code>'J'</code>.
     */
    public static final char C_LONG			= 'J';

    /**
     * Character constant indicating the primitive type short in a signature.
     * Value is <code>'S'</code>.
     */
    public static final char C_SHORT		= 'S';

    /**
     * Character constant indicating result type void in a signature.
     * Value is <code>'V'</code>.
     */
    public static final char C_VOID			= 'V';

    /**
     * Character constant indicating the start of a resolved type variable in a
     * signature. Value is <code>'T'</code>.
     * @since 3.0
     */
    public static final char C_TYPE_VARIABLE	= 'T';

    /**
     * Character constant indicating an unbound wildcard type argument
     * in a signature.
     * Value is <code>'*'</code>.
     * @since 3.0
     */
    public static final char C_STAR	= '*';

    /**
     * Character constant indicating an exception in a signature.
     * Value is <code>'^'</code>.
     * @since 3.1
     */
    public static final char C_EXCEPTION_START	= '^';

    /**
     * Character constant indicating a bound wildcard type argument
     * in a signature with extends clause.
     * Value is <code>'+'</code>.
     * @since 3.1
     */
    public static final char C_EXTENDS	= '+';

    /**
     * Character constant indicating a bound wildcard type argument
     * in a signature with super clause.
     * Value is <code>'-'</code>.
     * @since 3.1
     */
    public static final char C_SUPER	= '-';

    /**
     * Character constant indicating the dot in a signature.
     * Value is <code>'.'</code>.
     */
    public static final char C_DOT			= '.';

    /**
     * Character constant indicating the dollar in a signature.
     * Value is <code>'$'</code>.
     */
    public static final char C_DOLLAR			= '$';

    /**
     * Character constant indicating an array type in a signature.
     * Value is <code>'['</code>.
     */
    public static final char C_ARRAY		= '[';

    /**
     * Character constant indicating the start of a resolved, named type in a
     * signature. Value is <code>'L'</code>.
     */
    public static final char C_RESOLVED		= 'L';

    /**
     * Character constant indicating the start of an unresolved, named type in a
     * signature. Value is <code>'Q'</code>.
     */
    public static final char C_UNRESOLVED	= 'Q';

    /**
     * Character constant indicating the end of a named type in a signature.
     * Value is <code>';'</code>.
     */
    public static final char C_NAME_END		= ';';

    /**
     * Character constant indicating the start of a parameter type list in a
     * signature. Value is <code>'('</code>.
     */
    public static final char C_PARAM_START	= '(';

    /**
     * Character constant indicating the end of a parameter type list in a
     * signature. Value is <code>')'</code>.
     */
    public static final char C_PARAM_END	= ')';

    /**
     * Character constant indicating the start of a formal type parameter
     * (or type argument) list in a signature. Value is <code>'&lt;'</code>.
     * @since 3.0
     */
    public static final char C_GENERIC_START	= '<';

    /**
     * Character constant indicating the end of a generic type list in a
     * signature. Value is <code>'&gt;'</code>.
     * @since 3.0
     */
    public static final char C_GENERIC_END	= '>';

    /**
     * Character constant indicating a capture of a wildcard type in a
     * signature. Value is <code>'!'</code>.
     * @since 3.1
     */
    public static final char C_CAPTURE	= '!';

    public interface Displayable {
        String displayString(Object o);
    }

    private static final int DEFAULT_WRITING_SIZE = 1024;
    public final static String UTF_8 = "UTF-8";	//$NON-NLS-1$
    public static final String LINE_SEPARATOR = System.getProperty("line.separator"); //$NON-NLS-1$

    public static final String EMPTY_STRING = new String(CharOperation.NO_CHAR);
    /**
     * @since 3.14
     */
    public static final String COMMA_SEPARATOR = new String(CharOperation.COMMA_SEPARATOR);
    public static final int[] EMPTY_INT_ARRAY= new int[0];

    /**
     * Build all the directories and subdirectories corresponding to the packages names
     * into the directory specified in parameters.
     *
     * outputPath is formed like:
     *	   c:\temp\ the last character is a file separator
     * relativeFileName is formed like:
     *     java\lang\String.class *
     *
     * @param outputPath java.lang.String
     * @param relativeFileName java.lang.String
     * @return java.lang.String
     */
    public static String buildAllDirectoriesInto(String outputPath, String relativeFileName) throws IOException {
        char fileSeparatorChar = File.separatorChar;
        String fileSeparator = File.separator;
        File f;
        outputPath = outputPath.replace('/', fileSeparatorChar);
        // these could be optimized out if we normalized paths once and for
        // all
        relativeFileName = relativeFileName.replace('/', fileSeparatorChar);
        String outputDirPath, fileName;
        int separatorIndex = relativeFileName.lastIndexOf(fileSeparatorChar);
        if (separatorIndex == -1) {
            if (outputPath.endsWith(fileSeparator)) {
                outputDirPath = outputPath.substring(0, outputPath.length() - 1);
                fileName = outputPath + relativeFileName;
            } else {
                outputDirPath = outputPath;
                fileName = outputPath + fileSeparator + relativeFileName;
            }
        } else {
            if (outputPath.endsWith(fileSeparator)) {
                outputDirPath = outputPath +
                        relativeFileName.substring(0, separatorIndex);
                fileName = outputPath + relativeFileName;
            } else {
                outputDirPath = outputPath + fileSeparator +
                        relativeFileName.substring(0, separatorIndex);
                fileName = outputPath + fileSeparator + relativeFileName;
            }
        }
        f = new File(outputDirPath);
        f.mkdirs();
        if (f.isDirectory()) {
            return fileName;
        } else {
            // the directory creation failed for some reason - retry using
            // a slower algorithm so as to refine the diagnostic
            if (outputPath.endsWith(fileSeparator)) {
                outputPath = outputPath.substring(0, outputPath.length() - 1);
            }
            f = new File(outputPath);
            boolean checkFileType = false;
            if (f.exists()) {
                checkFileType = true; // pre-existed
            } else {
                // we have to create that directory
                if (!f.mkdirs()) {
                    if (f.exists()) {
                        // someone else created f -- need to check its type
                        checkFileType = true;
                    } else {
                        // no one could create f -- complain
                        throw new IOException(Messages.bind(
                                Messages.output_notValidAll, f.getAbsolutePath()));
                    }
                }
            }
            if (checkFileType) {
                if (!f.isDirectory()) {
                    throw new IOException(Messages.bind(
                            Messages.output_isFile, f.getAbsolutePath()));
                }
            }
            StringBuilder outDir = new StringBuilder(outputPath);
            outDir.append(fileSeparator);
            StringTokenizer tokenizer =
                    new StringTokenizer(relativeFileName, fileSeparator);
            String token = tokenizer.nextToken();
            while (tokenizer.hasMoreTokens()) {
                f = new File(outDir.append(token).append(fileSeparator).toString());
                checkFileType = false; // reset
                if (f.exists()) {
                    checkFileType = true; // this is suboptimal, but it catches corner cases
                    // in which a regular file pre-exists
                } else {
                    // we have to create that directory
                    if (!f.mkdir()) {
                        if (f.exists()) {
                            // someone else created f -- need to check its type
                            checkFileType = true;
                        } else {
                            // no one could create f -- complain
                            throw new IOException(Messages.bind(
                                    Messages.output_notValid,
                                    outDir.substring(outputPath.length() + 1,
                                            outDir.length() - 1),
                                    outputPath));
                        }
                    }
                }
                if (checkFileType) {
                    if (!f.isDirectory()) {
                        throw new IOException(Messages.bind(
                                Messages.output_isFile, f.getAbsolutePath()));
                    }
                }
                token = tokenizer.nextToken();
            }
            // token contains the last one
            return outDir.append(token).toString();
        }
    }

    /**
     * Returns the given bytes as a char array using a given encoding (null means platform default).
     */
    public static char[] bytesToChar(byte[] bytes, String encoding) throws IOException {

        return getInputStreamAsCharArray(new ByteArrayInputStream(bytes), encoding);

    }

    /**
     * Returns the outer most enclosing type's visibility for the given TypeDeclaration
     * and visibility based on compiler options.
     */
    public static int computeOuterMostVisibility(TypeDeclaration typeDeclaration, int visibility) {
        while (typeDeclaration != null) {
            switch (typeDeclaration.modifiers & ExtraCompilerModifiers.AccVisibilityMASK) {
                case ClassFileConstants.AccPrivate:
                    visibility = ClassFileConstants.AccPrivate;
                    break;
                case ClassFileConstants.AccDefault:
                    if (visibility != ClassFileConstants.AccPrivate) {
                        visibility = ClassFileConstants.AccDefault;
                    }
                    break;
                case ClassFileConstants.AccProtected:
                    if (visibility == ClassFileConstants.AccPublic) {
                        visibility = ClassFileConstants.AccProtected;
                    }
                    break;
            }
            typeDeclaration = typeDeclaration.enclosingType;
        }
        return visibility;
    }
    /**
     * Returns the contents of the given file as a byte array.
     * @throws IOException if a problem occured reading the file.
     */
    public static byte[] getFileByteContent(File file) throws IOException {
        InputStream stream = null;
        try {
            stream = new BufferedInputStream(new FileInputStream(file));
            return getInputStreamAsByteArray(stream);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
    /**
     * Returns the contents of the given file as a char array.
     * When encoding is null, then the platform default one is used
     * @throws IOException if a problem occured reading the file.
     */
    public static char[] getFileCharContent(File file, String encoding) throws IOException {
        InputStream stream = null;
        try {
            stream = new FileInputStream(file);
            return getInputStreamAsCharArray(stream, encoding);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
    private static FileOutputStream getFileOutputStream(boolean generatePackagesStructure, String outputPath, String relativeFileName) throws IOException {
        if (generatePackagesStructure) {
            return new FileOutputStream(new File(buildAllDirectoriesInto(outputPath, relativeFileName)));
        } else {
            String fileName = null;
            char fileSeparatorChar = File.separatorChar;
            String fileSeparator = File.separator;
            // First we ensure that the outputPath exists
            outputPath = outputPath.replace('/', fileSeparatorChar);
            // To be able to pass the mkdirs() method we need to remove the extra file separator at the end of the outDir name
            int indexOfPackageSeparator = relativeFileName.lastIndexOf(fileSeparatorChar);
            if (indexOfPackageSeparator == -1) {
                if (outputPath.endsWith(fileSeparator)) {
                    fileName = outputPath + relativeFileName;
                } else {
                    fileName = outputPath + fileSeparator + relativeFileName;
                }
            } else {
                int length = relativeFileName.length();
                if (outputPath.endsWith(fileSeparator)) {
                    fileName = outputPath + relativeFileName.substring(indexOfPackageSeparator + 1, length);
                } else {
                    fileName = outputPath + fileSeparator + relativeFileName.substring(indexOfPackageSeparator + 1, length);
                }
            }
            return new FileOutputStream(new File(fileName));
        }
    }

    /**
     * Returns the given input stream's contents as a byte array.
     * All bytes in the stream are returned.
     * Note this doesn't close the stream.
     * @throws IOException if a problem occurred reading the stream.
     */
    public static byte[] getInputStreamAsByteArray(InputStream input) throws IOException {
        // replaced, android does not support newer methods
//        return input.readAllBytes(); // will have even slightly better performance as of JDK17+ see JDK-8264777
        return readAllBytes(input);
    }

    public static byte[] readAllBytes(InputStream inputStream) throws IOException {
        final int bufLen = 4 * 0x400; // 4KB
        byte[] buf = new byte[bufLen];
        int readLen;
        IOException exception = null;

        try {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                while ((readLen = inputStream.read(buf, 0, bufLen)) != -1)
                    outputStream.write(buf, 0, readLen);

                return outputStream.toByteArray();
            }
        } catch (IOException e) {
            exception = e;
            throw e;
        } finally {
            if (exception == null) inputStream.close();
            else try {
                inputStream.close();
            } catch (IOException e) {
                exception.addSuppressed(e);
            }
        }
    }

    public static int readNBytes(InputStream is, byte[] b, int off, int len) throws IOException {
        Objects.requireNonNull(b);
        if (off < 0 || len < 0 || len > b.length - off)
            throw new IndexOutOfBoundsException();
        int n = 0;
        while (n < len) {
            int count = is.read(b, off + n, len - n);
            if (count < 0)
                break;
            n += count;
        }
        return n;
    }


    /**
     * Returns the given input stream's first bytes as array.
     * Note this doesn't close the stream.
     * @throws IOException if a problem occurred reading the stream.
     */
    public static byte[] readNBytes(InputStream input, int byteLength) throws IOException {
        return readAllBytes(input);
    }

    private static Map<String, byte[]> bomByEncoding = new HashMap<String, byte[]>();
    static {
        // org.eclipse.core.runtime.content.IContentDescription.BOM_UTF_8:
        bomByEncoding.put("UTF-8", new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }); //$NON-NLS-1$
        // XXX UTF-16, UTF-32 may have BOM too
        // @see org.eclipse.core.runtime.content.IContentDescription.BOM_UTF_16BE ,..
    }

    /**
     * Returns the given input stream's contents as a character array.
     * Note this doesn't close the stream.
     * @throws IOException if a problem occured reading the stream.
     */
    public static char[] getInputStreamAsCharArray(InputStream stream,  String encoding)
            throws IOException {
        byte[] byteContents =  getInputStreamAsByteArray(stream);

        Charset charset;
        try {
            charset = Charset.forName(encoding);
        } catch (IllegalArgumentException e) {
            // encoding is not supported
            charset = Charset.defaultCharset();
        }

        // check for BOM in encoded byte content
        // (instead of after decoding to avoid array copy after decoding):
        byte[] bom = bomByEncoding.get(charset.name());
        int start;
        if (bom != null && startsWith(byteContents, bom)) {
            start = bom.length; // skip BOM
        } else {
            start = 0;
        }

        return decode(byteContents, start, byteContents.length - start, charset);
    }

    /**
     * conversionless inmplementation of
     *
     * @return new String(srcBytes, start, length, charset).toCharArray();
     **/
    private static char[] decode(byte[] srcBytes, int start, int length, Charset charset) {
        ByteBuffer srcBuffer = ByteBuffer.wrap(srcBytes, start, length);
        CharBuffer destBuffer = charset.decode(srcBuffer);
        char[] dst = destBuffer.array();
        int chars = destBuffer.remaining();
        if (chars != dst.length) {
            dst = Arrays.copyOf(dst, chars);
        }
        return dst;
    }

    private static boolean startsWith(byte[] a, byte[] start) {
        if (a.length < start.length) {
            return false;
        }
        for (int i = 0; i < start.length; i++) {
            if (a[i] != start[i])
                return false;
        }
        return true;
    }

    /**
     * Returns a one line summary for an exception (extracted from its stacktrace: name + first frame)
     * @param exception
     * @return one line summary for an exception
     */
    public static String getExceptionSummary(Throwable exception) {
        StringWriter stringWriter = new StringWriter();
        exception.printStackTrace(new PrintWriter(stringWriter));
        StringBuffer buffer = stringWriter.getBuffer();
        StringBuffer exceptionBuffer = new StringBuffer(50);
        exceptionBuffer.append(exception.toString());
        // only keep leading frame portion of the trace (i.e. line no. 2 from the stacktrace)
        lookupLine2: for (int i = 0, lineSep = 0, max = buffer.length(), line2Start = 0; i < max; i++) {
            switch (buffer.charAt(i)) {
                case '\n':
                case '\r' :
                    if (line2Start > 0) {
                        exceptionBuffer.append(' ').append(buffer.substring(line2Start, i));
                        break lookupLine2;
                    }
                    lineSep++;
                    break;
                case ' ' :
                case '\t' :
                    break;
                default :
                    if (lineSep > 0) {
                        line2Start = i;
                        lineSep = 0;
                    }
                    break;
            }
        }
        return exceptionBuffer.toString();
    }

    public static int getLineNumber(int position, int[] lineEnds, int g, int d) {
        if (lineEnds == null)
            return 1;
        if (d == -1)
            return 1;
        int m = g, start;
        while (g <= d) {
            m = g + (d - g) /2;
            if (position < (start = lineEnds[m])) {
                d = m-1;
            } else if (position > start) {
                g = m+1;
            } else {
                return m + 1;
            }
        }
        if (position < lineEnds[m]) {
            return m+1;
        }
        return m+2;
    }
    /**
     * Returns the contents of the given zip entry as a byte array.
     * @throws IOException if a problem occurred reading the zip entry.
     */
    public static byte[] getZipEntryByteContent(ZipEntry ze, ZipFile zip)
            throws IOException {

        InputStream stream = null;
        try {
            InputStream inputStream = zip.getInputStream(ze);
            if (inputStream == null) throw new IOException("Invalid zip entry name : " + ze.getName()); //$NON-NLS-1$
            stream = new BufferedInputStream(inputStream);
            return readNBytes(stream, (int) ze.getSize());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
    public static int hashCode(Object[] array) {
        int prime = 31;
        if (array == null) {
            return 0;
        }
        int result = 1;
        for (int index = 0; index < array.length; index++) {
            result = prime * result + (array[index] == null ? 0 : array[index].hashCode());
        }
        return result;
    }
    /**
     * Returns whether the given name is potentially a zip archive file name
     * (it has a file extension and it is not ".java" nor ".class")
     */
    public final static boolean isPotentialZipArchive(String name) {
        int lastDot = name.lastIndexOf('.');
        if (lastDot == -1)
            return false; // no file extension, it cannot be a zip archive name
        if (name.lastIndexOf(File.separatorChar) > lastDot)
            return false; // dot was before the last file separator, it cannot be a zip archive name
        int length = name.length();
        int extensionLength = length - lastDot - 1;
        if (extensionLength == EXTENSION_java.length()) {
            for (int i = extensionLength-1; i >=0; i--) {
                if (Character.toLowerCase(name.charAt(length - extensionLength + i)) != EXTENSION_java.charAt(i)) {
                    break; // not a ".java" file, check ".class" file case below
                }
                if (i == 0) {
                    return false; // it is a ".java" file, it cannot be a zip archive name
                }
            }
        }
        if (extensionLength == EXTENSION_class.length()) {
            for (int i = extensionLength-1; i >=0; i--) {
                if (Character.toLowerCase(name.charAt(length - extensionLength + i)) != EXTENSION_class.charAt(i)) {
                    return true; // not a ".class" file, so this is a potential archive name
                }
            }
            return false; // it is a ".class" file, it cannot be a zip archive name
        }
        return true; // it is neither a ".java" file nor a ".class" file, so this is a potential archive name
    }

    public static final int ZIP_FILE = 0;
    public static final int JMOD_FILE = 1;

    /**
     * Returns the kind of archive this file is. The format is one of
     * #ZIP_FILE or {@link #JMOD_FILE}
     */
    public final static int archiveFormat(String name) {
        int lastDot = name.lastIndexOf('.');
        if (lastDot == -1)
            return -1; // no file extension, it cannot be a zip archive name
        if (name.lastIndexOf(File.separatorChar) > lastDot)
            return -1; // dot was before the last file separator, it cannot be a zip archive name
        int length = name.length();
        int extensionLength = length - lastDot - 1;

        if (extensionLength == EXTENSION_java.length()) {
            for (int i = extensionLength-1; i >=0; i--) {
                if (Character.toLowerCase(name.charAt(length - extensionLength + i)) != EXTENSION_java.charAt(i)) {
                    break; // not a ".java" file, check ".class" file case below
                }
                if (i == 0) {
                    return -1; // it is a ".java" file, it cannot be a zip archive name
                }
            }
        }
        if (extensionLength == EXTENSION_class.length()) {
            for (int i = extensionLength-1; i >=0; i--) {
                if (Character.toLowerCase(name.charAt(length - extensionLength + i)) != EXTENSION_class.charAt(i)) {
                    return ZIP_FILE; // not a ".class" file, so this is a potential archive name
                }
            }
            return -1; // it is a ".class" file, it cannot be a zip archive name
        }
        if (extensionLength == EXTENSION_jmod.length()) {
            for (int i = extensionLength-1; i >=0; i--) {
                if (Character.toLowerCase(name.charAt(length - extensionLength + i)) != EXTENSION_jmod.charAt(i)) {
                    return ZIP_FILE; // not a ".jmod" file, so this is a potential archive name
                }
            }
            return JMOD_FILE;
        }
        return ZIP_FILE; // it is neither a ".java" file nor a ".class" file, so this is a potential archive name
    }

    /**
     * Returns true iff str.toLowerCase().endsWith(".class")
     * implementation is not creating extra strings.
     */
    public final static boolean isClassFileName(char[] name) {
        int nameLength = name == null ? 0 : name.length;
        int suffixLength = SUFFIX_CLASS.length;
        if (nameLength < suffixLength) return false;

        for (int i = 0, offset = nameLength - suffixLength; i < suffixLength; i++) {
            char c = name[offset + i];
            if (c != SUFFIX_class[i] && c != SUFFIX_CLASS[i]) return false;
        }
        return true;
    }
    /**
     * Returns true iff str.toLowerCase().endsWith(".class")
     * implementation is not creating extra strings.
     */
    public final static boolean isClassFileName(String name) {
        int nameLength = name == null ? 0 : name.length();
        int suffixLength = SUFFIX_CLASS.length;
        if (nameLength < suffixLength) return false;

        for (int i = 0; i < suffixLength; i++) {
            char c = name.charAt(nameLength - i - 1);
            int suffixIndex = suffixLength - i - 1;
            if (c != SUFFIX_class[suffixIndex] && c != SUFFIX_CLASS[suffixIndex]) return false;
        }
        return true;
    }
    /* TODO (philippe) should consider promoting it to CharOperation
     * Returns whether the given resource path matches one of the inclusion/exclusion
     * patterns.
     * NOTE: should not be asked directly using pkg root pathes
     * @see IClasspathEntry#getInclusionPatterns
     * @see IClasspathEntry#getExclusionPatterns
     */
    public final static boolean isExcluded(char[] path, char[][] inclusionPatterns, char[][] exclusionPatterns, boolean isFolderPath) {
        if (inclusionPatterns == null && exclusionPatterns == null) return false;

        inclusionCheck: if (inclusionPatterns != null) {
            for (int i = 0, length = inclusionPatterns.length; i < length; i++) {
                char[] pattern = inclusionPatterns[i];
                char[] folderPattern = pattern;
                if (isFolderPath) {
                    int lastSlash = CharOperation.lastIndexOf('/', pattern);
                    if (lastSlash != -1 && lastSlash != pattern.length-1){ // trailing slash -> adds '**' for free (see http://ant.apache.org/manual/dirtasks.html)
                        int star = CharOperation.indexOf('*', pattern, lastSlash);
                        if ((star == -1
                                || star >= pattern.length-1
                                || pattern[star+1] != '*')) {
                            folderPattern = CharOperation.subarray(pattern, 0, lastSlash);
                        }
                    }
                }
                if (CharOperation.pathMatch(folderPattern, path, true, '/')) {
                    break inclusionCheck;
                }
            }
            return true; // never included
        }
        if (isFolderPath) {
            path = CharOperation.concat(path, new char[] {'*'}, '/');
        }
        if (exclusionPatterns != null) {
            for (int i = 0, length = exclusionPatterns.length; i < length; i++) {
                if (CharOperation.pathMatch(exclusionPatterns[i], path, true, '/')) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true iff str.toLowerCase().endsWith(".java")
     * implementation is not creating extra strings.
     */
    public final static boolean isJavaFileName(char[] name) {
        int nameLength = name == null ? 0 : name.length;
        int suffixLength = SUFFIX_JAVA.length;
        if (nameLength < suffixLength) return false;

        for (int i = 0, offset = nameLength - suffixLength; i < suffixLength; i++) {
            char c = name[offset + i];
            if (c != SUFFIX_java[i] && c != SUFFIX_JAVA[i]) return false;
        }
        return true;
    }

    /**
     * Returns true iff str.toLowerCase().endsWith(".java")
     * implementation is not creating extra strings.
     */
    public final static boolean isJavaFileName(String name) {
        int nameLength = name == null ? 0 : name.length();
        int suffixLength = SUFFIX_JAVA.length;
        if (nameLength < suffixLength) return false;

        for (int i = 0; i < suffixLength; i++) {
            char c = name.charAt(nameLength - i - 1);
            int suffixIndex = suffixLength - i - 1;
            if (c != SUFFIX_java[suffixIndex] && c != SUFFIX_JAVA[suffixIndex]) return false;
        }
        return true;
    }

    /**
     * Returns true iff str.toLowerCase().endsWith("jrt-fs.jar")
     * implementation is not creating extra strings.
     */
    public final static boolean isJrt(String name) {
        return name.endsWith(JRTUtil.JRT_FS_JAR);
    }

    public static void reverseQuickSort(char[][] list, int left, int right) {
        int original_left= left;
        int original_right= right;
        char[] mid= list[left + ((right-left)/2)];
        do {
            while (CharOperation.compareTo(list[left], mid) > 0) {
                left++;
            }
            while (CharOperation.compareTo(mid, list[right]) > 0) {
                right--;
            }
            if (left <= right) {
                char[] tmp= list[left];
                list[left]= list[right];
                list[right]= tmp;
                left++;
                right--;
            }
        } while (left <= right);
        if (original_left < right) {
            reverseQuickSort(list, original_left, right);
        }
        if (left < original_right) {
            reverseQuickSort(list, left, original_right);
        }
    }
    public static void reverseQuickSort(char[][] list, int left, int right, int[] result) {
        int original_left= left;
        int original_right= right;
        char[] mid= list[left + ((right-left)/2)];
        do {
            while (CharOperation.compareTo(list[left], mid) > 0) {
                left++;
            }
            while (CharOperation.compareTo(mid, list[right]) > 0) {
                right--;
            }
            if (left <= right) {
                char[] tmp= list[left];
                list[left]= list[right];
                list[right]= tmp;
                int temp = result[left];
                result[left] = result[right];
                result[right] = temp;
                left++;
                right--;
            }
        } while (left <= right);
        if (original_left < right) {
            reverseQuickSort(list, original_left, right, result);
        }
        if (left < original_right) {
            reverseQuickSort(list, left, original_right, result);
        }
    }
    /**
     * INTERNAL USE-ONLY
     * Search the column number corresponding to a specific position
     */
    public static final int searchColumnNumber(int[] startLineIndexes, int lineNumber, int position) {
        switch(lineNumber) {
            case 1 :
                return position + 1;
            case 2:
                return position - startLineIndexes[0];
            default:
                int line = lineNumber - 2;
                int length = startLineIndexes.length;
                if (line >= length) {
                    return position - startLineIndexes[length - 1];
                }
                return position - startLineIndexes[line];
        }
    }

    /**
     * Converts a boolean value into Boolean.
     * @param bool The boolean to convert
     * @return The corresponding Boolean object (TRUE or FALSE).
     */
    public static Boolean toBoolean(boolean bool) {
        if (bool) {
            return Boolean.TRUE;
        } else {
            return Boolean.FALSE;
        }
    }
    /**
     * Converts an array of Objects into String.
     */
    public static String toString(Object[] objects) {
        return toString(objects,
                new Displayable(){
                    @Override
                    public String displayString(Object o) {
                        if (o == null) return "null"; //$NON-NLS-1$
                        return o.toString();
                    }
                });
    }

    /**
     * Converts an array of Objects into String.
     */
    public static String toString(Object[] objects, Displayable renderer) {
        if (objects == null) return ""; //$NON-NLS-1$
        StringBuilder buffer = new StringBuilder(10);
        for (int i = 0; i < objects.length; i++){
            if (i > 0) buffer.append(", "); //$NON-NLS-1$
            buffer.append(renderer.displayString(objects[i]));
        }
        return buffer.toString();
    }

    /**
     * outputPath is formed like:
     *	   c:\temp\ the last character is a file separator
     * relativeFileName is formed like:
     *     java\lang\String.class
     * @param generatePackagesStructure a flag to know if the packages structure has to be generated.
     * @param outputPath the given output directory
     * @param relativeFileName the given relative file name
     * @param classFile the given classFile to write
     *
     */
    public static void writeToDisk(boolean generatePackagesStructure, String outputPath, String relativeFileName, ClassFile classFile) throws IOException {
        FileOutputStream file = getFileOutputStream(generatePackagesStructure, outputPath, relativeFileName);
		/* use java.nio to write
		if (true) {
			FileChannel ch = file.getChannel();
			try {
				ByteBuffer buffer = ByteBuffer.allocate(classFile.headerOffset + classFile.contentsOffset);
				buffer.put(classFile.header, 0, classFile.headerOffset);
				buffer.put(classFile.contents, 0, classFile.contentsOffset);
				buffer.flip();
				while (true) {
					if (ch.write(buffer) == 0) break;
				}
			} finally {
				ch.close();
			}
			return;
		}
		*/
        try (BufferedOutputStream output = new BufferedOutputStream(file, DEFAULT_WRITING_SIZE);) {
            // if no IOException occured, output cannot be null
            output.write(classFile.header, 0, classFile.headerOffset);
            output.write(classFile.contents, 0, classFile.contentsOffset);
            output.flush();
        } catch(IOException e) {
            throw e;
        }
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void recordNestedType(ClassFile classFile, TypeBinding typeBinding) {
        if (classFile.visitedTypes == null) {
            classFile.visitedTypes = new HashSet(3);
        } else if (classFile.visitedTypes.contains(typeBinding)) {
            // type is already visited
            return;
        }
        classFile.visitedTypes.add(typeBinding);
        if (typeBinding.isParameterizedType()
                && ((typeBinding.tagBits & TagBits.ContainsNestedTypeReferences) != 0)) {
            ParameterizedTypeBinding parameterizedTypeBinding = (ParameterizedTypeBinding) typeBinding;
            ReferenceBinding genericType = parameterizedTypeBinding.genericType();
            if ((genericType.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
                recordNestedType(classFile, genericType);
            }
            TypeBinding[] arguments = parameterizedTypeBinding.arguments;
            if (arguments != null) {
                for (int j = 0, max2 = arguments.length; j < max2; j++) {
                    TypeBinding argument = arguments[j];
                    if (argument.isWildcard()) {
                        WildcardBinding wildcardBinding = (WildcardBinding) argument;
                        TypeBinding bound = wildcardBinding.bound;
                        if (bound != null
                                && ((bound.tagBits & TagBits.ContainsNestedTypeReferences) != 0)) {
                            recordNestedType(classFile, bound);
                        }
                        ReferenceBinding superclass = wildcardBinding.superclass();
                        if (superclass != null
                                && ((superclass.tagBits & TagBits.ContainsNestedTypeReferences) != 0)) {
                            recordNestedType(classFile, superclass);
                        }
                        ReferenceBinding[] superInterfaces = wildcardBinding.superInterfaces();
                        if (superInterfaces != null) {
                            for (int k = 0, max3 =  superInterfaces.length; k < max3; k++) {
                                ReferenceBinding superInterface = superInterfaces[k];
                                if ((superInterface.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
                                    recordNestedType(classFile, superInterface);
                                }
                            }
                        }
                    } else if ((argument.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
                        recordNestedType(classFile, argument);
                    }
                }
            }
        } else if (typeBinding.isTypeVariable()
                && ((typeBinding.tagBits & TagBits.ContainsNestedTypeReferences) != 0)) {
            TypeVariableBinding typeVariableBinding = (TypeVariableBinding) typeBinding;
            TypeBinding upperBound = typeVariableBinding.upperBound();
            if (upperBound != null && ((upperBound.tagBits & TagBits.ContainsNestedTypeReferences) != 0)) {
                recordNestedType(classFile, upperBound);
            }
            TypeBinding[] upperBounds = typeVariableBinding.otherUpperBounds();
            if (upperBounds != null) {
                for (int k = 0, max3 =  upperBounds.length; k < max3; k++) {
                    TypeBinding otherUpperBound = upperBounds[k];
                    if ((otherUpperBound.tagBits & TagBits.ContainsNestedTypeReferences) != 0) {
                        recordNestedType(classFile, otherUpperBound);
                    }
                }
            }
        } else if (typeBinding.isNestedType()) {
            TypeBinding enclosingType = typeBinding;
            do {
                if (!enclosingType.canBeSeenBy(classFile.referenceBinding.scope))
                    break;
                enclosingType = enclosingType.enclosingType();
            } while (enclosingType != null);
            boolean onBottomForBug445231 = enclosingType != null;
            classFile.recordInnerClasses(typeBinding, onBottomForBug445231);
        }
    }
    /*
     * External API
     */
    public static File getJavaHome() {
        String javaHome = System.getProperty("java.home");//$NON-NLS-1$
        if (javaHome != null) {
            File javaHomeFile = new File(javaHome);
            if (javaHomeFile.exists()) {
                return javaHomeFile;
            }
        }
        return null;
    }

    public static void collectVMBootclasspath(List<Classpath> bootclasspaths, File javaHome) {
        List<Classpath> classpaths = collectPlatformLibraries(javaHome);
        bootclasspaths.addAll(classpaths);
    }
    public static void collectRunningVMBootclasspath(List<Classpath> bootclasspaths) {
        collectVMBootclasspath(bootclasspaths, null);
    }
    public static long getJDKLevel(File javaHome) {
        String version = System.getProperty("java.version"); //$NON-NLS-1$
        return CompilerOptions.versionToJdkLevel(version);
    }
    public static List<FileSystem.Classpath> collectFilesNames() {
        return collectPlatformLibraries(null);
    }
    public static List<FileSystem.Classpath> collectPlatformLibraries(File javaHome) {
        /* no bootclasspath specified
         * we can try to retrieve the default librairies of the VM used to run
         * the batch compiler
         */
        String javaversion = null;
        javaversion = System.getProperty("java.version"); //$NON-NLS-1$
        // Surely, this ain't required anymore?
        if (javaversion != null && javaversion.equalsIgnoreCase("1.1.8")) { //$NON-NLS-1$
            throw new IllegalStateException();
        }
        long jdkLevel = CompilerOptions.versionToJdkLevel(javaversion);
        if (jdkLevel >= ClassFileConstants.JDK9) {
            List<FileSystem.Classpath> filePaths = new ArrayList<>();
            if (javaHome == null) {
                javaHome = getJavaHome();
            }
            if (javaHome != null) {
                filePaths.add(FileSystem.getJrtClasspath(javaHome.getAbsolutePath(), null, null, null));
                return filePaths;
            }
        }

        /*
         * Handle >= JDK 1.2.2 settings: retrieve the bootclasspath
         */
        // check bootclasspath properties for Sun, JRockit and Harmony VMs
        String bootclasspathProperty = System.getProperty("sun.boot.class.path"); //$NON-NLS-1$
        if ((bootclasspathProperty == null) || (bootclasspathProperty.length() == 0)) {
            // IBM J9 VMs
            bootclasspathProperty = System.getProperty("vm.boot.class.path"); //$NON-NLS-1$
            if ((bootclasspathProperty == null) || (bootclasspathProperty.length() == 0)) {
                // Harmony using IBM VME
                bootclasspathProperty = System.getProperty("org.apache.harmony.boot.class.path"); //$NON-NLS-1$
            }
        }
        Set<String> filePaths = new HashSet<>();
        if ((bootclasspathProperty != null) && (bootclasspathProperty.length() != 0)) {
            StringTokenizer tokenizer = new StringTokenizer(bootclasspathProperty, File.pathSeparator);
            while (tokenizer.hasMoreTokens()) {
                filePaths.add(tokenizer.nextToken());
            }
        } else {
            // try to get all jars inside the lib folder of the java home
            if (javaHome == null) {
                javaHome = getJavaHome();
            }
            if (javaHome != null) {
                File[] directoriesToCheck = null;
                if (System.getProperty("os.name").startsWith("Mac")) {//$NON-NLS-1$//$NON-NLS-2$
                    directoriesToCheck = new File[] {
                            new File(javaHome, "../Classes"), //$NON-NLS-1$
                    };
                } else {
                    // fall back to try to retrieve them out of the lib directory
                    directoriesToCheck = new File[] {
                            new File(javaHome, "lib") //$NON-NLS-1$
                    };
                }
                File[][] systemLibrariesJars = Main.getLibrariesFiles(directoriesToCheck);
                if (systemLibrariesJars != null) {
                    for (int i = 0, max = systemLibrariesJars.length; i < max; i++) {
                        File[] current = systemLibrariesJars[i];
                        if (current != null) {
                            for (int j = 0, max2 = current.length; j < max2; j++) {
                                filePaths.add(current[j].getAbsolutePath());
                            }
                        }
                    }
                }
            }
        }
        List<FileSystem.Classpath> classpaths = new ArrayList<>();
        for (String filePath : filePaths) {
            FileSystem.Classpath currentClasspath = FileSystem.getClasspath(filePath, null, null, null, null);
            if (currentClasspath != null) {
                classpaths.add(currentClasspath);
            }
        }
        return classpaths;
    }
    public static int getParameterCount(char[] methodSignature) {
        try {
            int count = 0;
            int i = CharOperation.indexOf(C_PARAM_START, methodSignature);
            if (i < 0) {
                throw new IllegalArgumentException(String.valueOf(methodSignature));
            } else {
                i++;
            }
            for (;;) {
                if (methodSignature[i] == C_PARAM_END) {
                    return count;
                }
                int e= Util.scanTypeSignature(methodSignature, i);
                if (e < 0) {
                    throw new IllegalArgumentException(String.valueOf(methodSignature));
                } else {
                    i = e + 1;
                }
                count++;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException(String.valueOf(methodSignature), e);
        }
    }

    /**
     * Scans the given string for a type signature starting at the given index
     * and returns the index of the last character.
     * <pre>
     * TypeSignature:
     *  |  BaseTypeSignature
     *  |  ArrayTypeSignature
     *  |  ClassTypeSignature
     *  |  TypeVariableSignature
     * </pre>
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a type signature
     */
    public static int scanTypeSignature(char[] string, int start) {
        // need a minimum 1 char
        if (start >= string.length) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        switch (c) {
            case C_ARRAY :
                return scanArrayTypeSignature(string, start);
            case C_RESOLVED :
            case C_UNRESOLVED :
                return scanClassTypeSignature(string, start);
            case C_TYPE_VARIABLE :
                return scanTypeVariableSignature(string, start);
            case C_BOOLEAN :
            case C_BYTE :
            case C_CHAR :
            case C_DOUBLE :
            case C_FLOAT :
            case C_INT :
            case C_LONG :
            case C_SHORT :
            case C_VOID :
                return scanBaseTypeSignature(string, start);
            case C_CAPTURE :
                return scanCaptureTypeSignature(string, start);
            case C_EXTENDS:
            case C_SUPER:
            case C_STAR:
                return scanTypeBoundSignature(string, start);
            default :
                throw newIllegalArgumentException(string, start);
        }
    }

    /**
     * Scans the given string for a base type signature starting at the given index
     * and returns the index of the last character.
     * <pre>
     * BaseTypeSignature:
     *     <b>B</b> | <b>C</b> | <b>D</b> | <b>F</b> | <b>I</b>
     *   | <b>J</b> | <b>S</b> | <b>V</b> | <b>Z</b>
     * </pre>
     * Note that although the base type "V" is only allowed in method return types,
     * there is no syntactic ambiguity. This method will accept them anywhere
     * without complaint.
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a base type signature
     */
    public static int scanBaseTypeSignature(char[] string, int start) {
        // need a minimum 1 char
        if (start >= string.length) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        if ("BCDFIJSVZ".indexOf(c) >= 0) { //$NON-NLS-1$
            return start;
        } else {
            throw newIllegalArgumentException(string, start);
        }
    }

    /**
     * Scans the given string for an array type signature starting at the given
     * index and returns the index of the last character.
     * <pre>
     * ArrayTypeSignature:
     *     <b>[</b> TypeSignature
     * </pre>
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not an array type signature
     */
    public static int scanArrayTypeSignature(char[] string, int start) {
        int length = string.length;
        // need a minimum 2 char
        if (start >= length - 1) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        if (c != C_ARRAY) {
            throw newIllegalArgumentException(string, start);
        }

        c = string[++start];
        while(c == C_ARRAY) {
            // need a minimum 2 char
            if (start >= length - 1) {
                throw newIllegalArgumentException(string, start);
            }
            c = string[++start];
        }
        return scanTypeSignature(string, start);
    }

    /**
     * Scans the given string for a capture of a wildcard type signature starting at the given
     * index and returns the index of the last character.
     * <pre>
     * CaptureTypeSignature:
     *     <b>!</b> TypeBoundSignature
     * </pre>
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a capture type signature
     */
    public static int scanCaptureTypeSignature(char[] string, int start) {
        // need a minimum 2 char
        if (start >= string.length - 1) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        if (c != C_CAPTURE) {
            throw newIllegalArgumentException(string, start);
        }
        return scanTypeBoundSignature(string, start + 1);
    }

    /**
     * Scans the given string for a type variable signature starting at the given
     * index and returns the index of the last character.
     * <pre>
     * TypeVariableSignature:
     *     <b>T</b> Identifier <b>;</b>
     * </pre>
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a type variable signature
     */
    public static int scanTypeVariableSignature(char[] string, int start) {
        // need a minimum 3 chars "Tx;"
        if (start >= string.length - 2) {
            throw newIllegalArgumentException(string, start);
        }
        // must start in "T"
        char c = string[start];
        if (c != C_TYPE_VARIABLE) {
            throw newIllegalArgumentException(string, start);
        }
        int id = scanIdentifier(string, start + 1);
        c = string[id + 1];
        if (c == C_SEMICOLON) {
            return id + 1;
        } else {
            throw newIllegalArgumentException(string, start);
        }
    }

    /**
     * Scans the given string for an identifier starting at the given
     * index and returns the index of the last character.
     * Stop characters are: ";", ":", "&lt;", "&gt;", "/", ".".
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not an identifier
     */
    public static int scanIdentifier(char[] string, int start) {
        // need a minimum 1 char
        if (start >= string.length) {
            throw newIllegalArgumentException(string, start);
        }
        int p = start;
        while (true) {
            char c = string[p];
            if (c == '<' || c == '>' || c == ':' || c == ';' || c == '.' || c == '/') {
                return p - 1;
            }
            p++;
            if (p == string.length) {
                return p - 1;
            }
        }
    }

    /**
     * Scans the given string for a class type signature starting at the given
     * index and returns the index of the last character.
     * <pre>
     * ClassTypeSignature:
     *     { <b>L</b> | <b>Q</b> } Identifier
     *           { { <b>/</b> | <b>.</b> Identifier [ <b>&lt;</b> TypeArgumentSignature* <b>&gt;</b> ] }
     *           <b>;</b>
     * </pre>
     * Note that although all "/"-identifiers most come before "."-identifiers,
     * there is no syntactic ambiguity. This method will accept them without
     * complaint.
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a class type signature
     */
    public static int scanClassTypeSignature(char[] string, int start) {
        // need a minimum 3 chars "Lx;"
        if (start >= string.length - 2) {
            throw newIllegalArgumentException(string, start);
        }
        // must start in "L" or "Q"
        char c = string[start];
        if (c != C_RESOLVED && c != C_UNRESOLVED) {
            return -1;
        }
        int p = start + 1;
        while (true) {
            if (p >= string.length) {
                throw newIllegalArgumentException(string, start);
            }
            c = string[p];
            if (c == C_SEMICOLON) {
                // all done
                return p;
            } else if (c == C_GENERIC_START) {
                int e = scanTypeArgumentSignatures(string, p);
                p = e;
            } else if (c == C_DOT || c == '/') {
                int id = scanIdentifier(string, p + 1);
                p = id;
            }
            p++;
        }
    }

    /**
     * Scans the given string for a type bound signature starting at the given
     * index and returns the index of the last character.
     * <pre>
     * TypeBoundSignature:
     *     <b>[-+]</b> TypeSignature <b>;</b>
     *     <b>*</b></b>
     * </pre>
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a type variable signature
     */
    public static int scanTypeBoundSignature(char[] string, int start) {
        // need a minimum 1 char for wildcard
        if (start >= string.length) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        switch (c) {
            case C_STAR :
                return start;
            case C_SUPER :
            case C_EXTENDS :
                break;
            default :
                // must start in "+/-"
                throw newIllegalArgumentException(string, start);
        }
        c = string[++start];
        if (c != C_STAR && start >= string.length -1) { // unless "-*" we need at least one more char, e.g. after "+[", other variants are even longer
            throw new IllegalArgumentException();
        }
        switch (c) {
            case C_CAPTURE :
                return scanCaptureTypeSignature(string, start);
            case C_SUPER :
            case C_EXTENDS :
                return scanTypeBoundSignature(string, start);
            case C_RESOLVED :
            case C_UNRESOLVED :
                return scanClassTypeSignature(string, start);
            case C_TYPE_VARIABLE :
                return scanTypeVariableSignature(string, start);
            case C_ARRAY :
                return scanArrayTypeSignature(string, start);
            case C_STAR:
                return start;
            default:
                throw newIllegalArgumentException(string, start);
        }
    }

    /**
     * Scans the given string for a list of type argument signatures starting at
     * the given index and returns the index of the last character.
     * <pre>
     * TypeArgumentSignatures:
     *     <b>&lt;</b> TypeArgumentSignature* <b>&gt;</b>
     * </pre>
     * Note that although there is supposed to be at least one type argument, there
     * is no syntactic ambiguity if there are none. This method will accept zero
     * type argument signatures without complaint.
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a list of type arguments
     * signatures
     */
    public static int scanTypeArgumentSignatures(char[] string, int start) {
        // need a minimum 2 char "<>"
        if (start >= string.length - 1) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        if (c != C_GENERIC_START) {
            throw newIllegalArgumentException(string, start);
        }
        int p = start + 1;
        while (true) {
            if (p >= string.length) {
                throw newIllegalArgumentException(string, start);
            }
            c = string[p];
            if (c == C_GENERIC_END) {
                return p;
            }
            int e = scanTypeArgumentSignature(string, p);
            p = e + 1;
        }
    }

    /**
     * Scans the given string for a type argument signature starting at the given
     * index and returns the index of the last character.
     * <pre>
     * TypeArgumentSignature:
     *     <b>&#42;</b>
     *  |  <b>+</b> TypeSignature
     *  |  <b>-</b> TypeSignature
     *  |  TypeSignature
     * </pre>
     * Note that although base types are not allowed in type arguments, there is
     * no syntactic ambiguity. This method will accept them without complaint.
     *
     * @param string the signature string
     * @param start the 0-based character index of the first character
     * @return the 0-based character index of the last character
     * @exception IllegalArgumentException if this is not a type argument signature
     */
    public static int scanTypeArgumentSignature(char[] string, int start) {
        // need a minimum 1 char
        if (start >= string.length) {
            throw newIllegalArgumentException(string, start);
        }
        char c = string[start];
        switch (c) {
            case C_STAR :
                return start;
            case C_EXTENDS :
            case C_SUPER :
                return scanTypeBoundSignature(string, start);
            default :
                return scanTypeSignature(string, start);
        }
    }

    public static boolean effectivelyEqual(Object [] one, Object [] two) {
        if (one == two)
            return true;
        int oneLength = one == null ? 0 : one.length;
        int twoLength = two == null ? 0 : two.length;
        if (oneLength != twoLength)
            return false;
        if (oneLength == 0)
            return true;
        for (int i = 0; i < one.length; i++) {
            if (one[i] != two[i])
                return false;
        }
        return true;
    }

    public static void appendEscapedChar(StringBuffer buffer, char c, boolean stringLiteral) {
        switch (c) {
            case '\b' :
                buffer.append("\\b"); //$NON-NLS-1$
                break;
            case '\t' :
                buffer.append("\\t"); //$NON-NLS-1$
                break;
            case '\n' :
                buffer.append("\\n"); //$NON-NLS-1$
                break;
            case '\f' :
                buffer.append("\\f"); //$NON-NLS-1$
                break;
            case '\r' :
                buffer.append("\\r"); //$NON-NLS-1$
                break;
            case '\"':
                if (stringLiteral) {
                    buffer.append("\\\""); //$NON-NLS-1$
                } else {
                    buffer.append(c);
                }
                break;
            case '\'':
                if (stringLiteral) {
                    buffer.append(c);
                } else {
                    buffer.append("\\\'"); //$NON-NLS-1$
                }
                break;
            case '\\':
                buffer.append("\\\\"); //$NON-NLS-1$
                break;
            default:
                if (c >= 0x20) {
                    buffer.append(c);
                } else if (c >= 0x10) {
                    buffer.append("\\u00").append(Integer.toHexString(c)); //$NON-NLS-1$
                } else if (c >= 0) {
                    buffer.append("\\u000").append(Integer.toHexString(c)); //$NON-NLS-1$
                } else {
                    buffer.append(c);
                }
        }
    }

    private static IllegalArgumentException newIllegalArgumentException(char[] string, int start) {
        return new IllegalArgumentException("\"" + String.valueOf(string) + "\" at " + start); //$NON-NLS-1$ //$NON-NLS-2$
    }
}