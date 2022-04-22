package com.tyron.builder.android.aapt2;

import org.jetbrains.annotations.NotNull;

import java.io.File;

/** Class containing the file renaming rules for {@code aapt2}. */
public final class Aapt2RenamingConventions {

    private Aapt2RenamingConventions() {}

    /**
     * Obtains the renaming for compilation for the given file. When compiling a file, {@code aapt2}
     * will output a file with a name that depends on the file being compiled, as well as its path.
     * This method will compute what the output name is for a given input.
     *
     * @param f the file
     * @return the new file's name (this will take the file's path into consideration)
     * @throws Aapt2Exception cannot analyze file path
     */
    @NotNull
    public static String compilationRename(@NotNull File f) throws Aapt2Exception {
        String fileName = f.getName();

        File fileParent = f.getParentFile();
        if (fileParent == null) {
            throw Aapt2Exception.create("Could not get parent of file '" + f.getAbsolutePath() + "'");
        }

        String parentName = fileParent.getName();

        /*
         * Split fileName into fileName and ext. If fileName does not have an extension, make ext
         * empty.
         */
        int extIdx = fileName.lastIndexOf('.');
        String ext = extIdx == -1 ? "" : fileName.substring(extIdx);
        fileName = extIdx == -1 ? fileName : fileName.substring(0, extIdx);

        /*
         * Values are compiled to arsc. This mirrors the implementation of the Compile method in
         * frameworks/base/tools/aapt2/compile/Compile.cpp
         *
         * e.g. values/strings.xml becomes values_strings.arsc.flat and not values_strings.xml.flat.
         */
        if (parentName.startsWith("values") && ext.equals(".xml")) {
            ext = ".arsc";
        }

        return parentName + "_" + fileName + ext + ".flat";
    }
}
