package com.tyron.builder.packaging;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.tools.build.apkzlib.zfile.NativeLibrariesPackagingMode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import kotlin.text.StringsKt;

/**
 * Utility class for packaging.
 */
public class PackagingUtils {

    /**
     * List of file formats which are already compressed or don't compress well, same as the one
     * used by aapt.
     */
    public static final ImmutableList<String> DEFAULT_AAPT_NO_COMPRESS_EXTENSIONS =
            ImmutableList.of(
                    ".jpg", ".jpeg", ".png", ".gif", ".opus", ".wav", ".mp2", ".mp3", ".ogg",
                    ".aac", ".mpg", ".mpeg", ".mid", ".midi", ".smf", ".jet", ".rtttl", ".imy",
                    ".xmf", ".mp4", ".m4a", ".m4v", ".3gp", ".3gpp", ".3g2", ".3gpp2", ".amr",
                    ".awb", ".wma", ".wmv", ".webm", ".mkv", ".webp");

    public static final ImmutableList<String> DEFAULT_NO_COMPRESS_FILE_NAMES =
            ImmutableList.of(
                    SdkConstants.FN_BINARY_ART_PROFILE,
                    SdkConstants.FN_BINARY_ART_PROFILE_METADATA);

    /** Set of characters that need to be escaped when creating an ECMAScript regular expression. */
    public static final ImmutableSet<Character> ECMA_SCRIPT_ESCAPABLE_CHARACTERS =
            ImmutableSet.of('^', '$', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|');

    /**
     * Checks a file to make sure it should be packaged as standard resources.
     * @param filePath OS-independent path of the file (including extension), relative to the
     *                 archive
     * @param allowClassFiles whether to allow java class files
     * @return true if the file should be packaged as standard java resources
     */
    public static boolean checkFileForApkPackaging(
            @NonNull String filePath, boolean allowClassFiles) {
        String fileName = new File(filePath).getName();

        // ignore hidden files and backup files
        return !isOfNonResourcesExtensions(Files.getFileExtension(fileName), allowClassFiles)
                && !filePath.equals("META-INF/MANIFEST.MF")
                && !isUsedForSigning(filePath)
                && !isMavenMetadata(filePath);
    }

    private static boolean isMavenMetadata(String filePath) {
        return filePath.startsWith("META-INF/maven");
    }

    private static boolean isUsedForSigning(String filePath) {
        if (!"META-INF".equals(new File(filePath).getParent())) {
            return false;
        }

        String fileExtension = Files.getFileExtension(filePath);
        for (String extension : SIGNING_EXTENSIONS) {
            if (fileExtension.equalsIgnoreCase(extension)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isOfNonResourcesExtensions(
            @NonNull String extension,
            boolean allowClassFiles) {
        for (String ext : NON_RESOURCES_EXTENSIONS) {
            if (ext.equalsIgnoreCase(extension)) {
                return true;
            }
        }

        return !allowClassFiles && SdkConstants.EXT_CLASS.equals(extension);
    }

    /**
     * List of file extensions that represents non resources files.
     */
    private static final ImmutableList<String> NON_RESOURCES_EXTENSIONS =
            ImmutableList.<String>builder()
                    .add("aidl")            // Aidl files
                    .add("rs")              // RenderScript files
                    .add("fs")              // FilterScript files
                    .add("rsh")             // RenderScript header files
                    .add("d")               // Dependency files
                    .add("java")            // Java files
                    .add("scala")           // Scala files
                    .add("scc")             // VisualSourceSafe
                    .add("swp")             // vi swap file
                    .build();

    /**
     * List of file extensions that are used for jar signing.
     */
    public static final ImmutableList<String> SIGNING_EXTENSIONS =
            ImmutableList.of("SF", "RSA", "DSA", "EC");

    @NonNull
    public static Predicate<String> getNoCompressPredicate(
            @Nullable Collection<String> aaptOptionsNoCompress,
            @NonNull NativeLibrariesPackagingMode nativeLibsPackagingMode,
            @NonNull DexPackagingMode dexPackagingMode) {
        ImmutableList.Builder<String> finalList = ImmutableList.builder();
        finalList.addAll(getAllNoCompressFileNames());
        getAllNoCompressExtensions(
                finalList, aaptOptionsNoCompress, nativeLibsPackagingMode, dexPackagingMode);
        return getNoCompressPredicateForExtensions(finalList.build());
    }

    @NonNull
    public static Predicate<String> getNoCompressPredicateForJavaRes(
            @NonNull Collection<String> aaptOptionsNoCompress) {
        ImmutableList.Builder<String> finalList = ImmutableList.builder();
        getAllNoCompressExtensions(
                finalList,
                aaptOptionsNoCompress,
                NativeLibrariesPackagingMode.COMPRESSED,
                DexPackagingMode.COMPRESSED);

        return getNoCompressPredicateForExtensions(finalList.build());
    }

    @NonNull
    public static List<String> getNoCompressGlobsForBundle(
            @NonNull Collection<String> aaptOptionsNoCompress) {
        ImmutableList.Builder<String> extensions = ImmutableList.builder();
        getAllNoCompressExtensions(
                extensions,
                aaptOptionsNoCompress,
                NativeLibrariesPackagingMode.COMPRESSED,
                // TODO(b/161461387) Does bundletool automatically uncompress dex for P+?
                DexPackagingMode.COMPRESSED);

        return extensions.build().stream()
                .map(PackagingUtils::toCaseInsensitiveGlobForBundle)
                .sorted()
                .collect(ImmutableList.toImmutableList());
    }

    private static String toCaseInsensitiveGlobForBundle(String glob) {
        return toCaseInsensitiveGlob(glob, "**", "[", "", "]", ImmutableList.of());
    }

    @NonNull
    public static List<String> getNoCompressForAapt(
            @NonNull Collection<String> aaptOptionsNoCompress) {
        return aaptOptionsNoCompress
                .stream()
                .map(PackagingUtils::toCaseInsensitiveGlobForAapt)
                .sorted()
                .collect(ImmutableList.toImmutableList());
    }

    private static String toCaseInsensitiveGlobForAapt(String glob) {
        return toCaseInsensitiveGlob(glob, "", "(", "|", ")", ECMA_SCRIPT_ESCAPABLE_CHARACTERS);
    }

    private static String toCaseInsensitiveGlob(
            String glob,
            String prefix,
            String alternativesStart,
            String alternativesSeparator,
            String alternativesEnd,
            Collection<Character> escapableCharacters) {
        // The bundle expects the format of "foo.bar" into "**[fF][oO][[oO].[bB][aA][rR]".
        // Aapt expects the format of "foo.bar" into "(f|F)(o|O)(o|O).(b|B)(a|A)(r|R)".
        StringBuilder sb = new StringBuilder(glob.length() + prefix.length());

        // Users can pass extensions to the no-compress list, so we need to append '**' for the
        // bundle. Aapt already expects them to be extensions and does not support '**'.
        sb.append(prefix);

        int index = 0;
        while (index < glob.length()) {
            int codePoint = glob.codePointAt(index);
            int upperCodePoint = Character.toUpperCase(codePoint);
            int lowerCodePoint = Character.toLowerCase(codePoint);
            // If the character can be changed to upper or lower case, make sure we accept both.
            // For example, if we encounter the char "a" it will generate "[aA]" or "(a|A)" which
            // means that either character can be matched. For other characters, e.g. "." just the
            // original character will be appended to the string builder.
            boolean mixedCase = codePoint != upperCodePoint || codePoint != lowerCodePoint;
            if (mixedCase) {
                sb.append(alternativesStart);
            }
            if (lowerCodePoint != codePoint) {
                sb.appendCodePoint(lowerCodePoint);
                sb.append(alternativesSeparator);
            }
            if (escapableCharacters.contains(glob.charAt(index))) {
                sb.append('\\');
            }
            sb.appendCodePoint(codePoint);
            if (upperCodePoint != codePoint) {
                sb.append(alternativesSeparator);
                sb.appendCodePoint(upperCodePoint);
            }
            if (mixedCase) {
                sb.append(alternativesEnd);
            }
            index += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    @NonNull
    public static NativeLibrariesPackagingMode getNativeLibrariesLibrariesPackagingMode(
            @Nullable Boolean extractNativeLibs) {
        // The default is "true", so we only package *.so files differently if
        // android:extractNativeLibs is explicitly set to "false".
        if (Boolean.FALSE.equals(extractNativeLibs)) {
            return NativeLibrariesPackagingMode.UNCOMPRESSED_AND_ALIGNED;
        } else {
            return NativeLibrariesPackagingMode.COMPRESSED;
        }
    }

    @NonNull
    public static DexPackagingMode getDexPackagingMode(
            @Nullable Boolean useEmbeddedDex, boolean useLegacyPackaging) {
        if (Boolean.TRUE.equals(useEmbeddedDex)) {
            // If useEmbeddedDex is true, dex files must be uncompressed.
            return DexPackagingMode.UNCOMPRESSED;
        } else if (useLegacyPackaging) {
            return DexPackagingMode.COMPRESSED;
        } else {
            return DexPackagingMode.UNCOMPRESSED;
        }
    }

    @VisibleForTesting
    @NonNull
    static Predicate<String> getNoCompressPredicateForExtensions(
            @NonNull Iterable<String> noCompressExtensions) {
        return name -> {
            for (String extension : noCompressExtensions) {
                // Check if the name ends with any of the no-compress extensions, ignoring the case
                // sensitivity.
                if (StringsKt.endsWith(name, extension, true)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static List<String> getAllNoCompressFileNames() {
        return DEFAULT_NO_COMPRESS_FILE_NAMES;
    }

    @NonNull
    private static void getAllNoCompressExtensions(
            @NonNull ImmutableList.Builder<String> into,
            @Nullable Collection<String> aaptOptionsNoCompress,
            @NonNull NativeLibrariesPackagingMode nativeLibrariesPackagingMode,
            @NonNull DexPackagingMode dexPackagingMode) {
        into.addAll(DEFAULT_AAPT_NO_COMPRESS_EXTENSIONS);

        // .tflite files should always be uncompressed (Issue 152875817)
        into.add(SdkConstants.DOT_TFLITE);

        if (nativeLibrariesPackagingMode == NativeLibrariesPackagingMode.UNCOMPRESSED_AND_ALIGNED) {
            into.add(SdkConstants.DOT_NATIVE_LIBS);
        }
        if (dexPackagingMode == DexPackagingMode.UNCOMPRESSED) {
            into.add(SdkConstants.DOT_DEX);
        }

        if (aaptOptionsNoCompress != null) {
            into.addAll(aaptOptionsNoCompress);
        }
    }
}