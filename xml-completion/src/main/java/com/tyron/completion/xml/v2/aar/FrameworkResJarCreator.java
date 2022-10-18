package com.tyron.completion.xml.v2.aar;

import com.android.utils.Base128OutputStream;
import com.google.common.annotations.VisibleForTesting;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;

/**
 * A command-line program for packaging framework resources into framework_res.jar. The jar file
 * created by this program contains compressed XML resource files and two binary files,
 * resources.bin and resources_light.bin. Format of these binary files is identical to format of
 * a framework resource cache file without a header. The resources.bin file contains a list of all
 * framework resources. The resources_light.bin file contains a list of resources excluding
 * locale-specific ones.
 */
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
public class FrameworkResJarCreator {
  public static void main(@NotNull String[] args) {
    if (args.length != 2) {
      printUsage(FrameworkResJarCreator.class.getName());
      System.exit(1);
    }

    Path resDirectory = Paths.get(args[0]).toAbsolutePath().normalize();
    Path jarFile = Paths.get(args[1]).toAbsolutePath().normalize();
    try {
      createJar(resDirectory, jarFile);
    }
    catch (IOException e) {
      e.printStackTrace();
    }
  }

  @VisibleForTesting
  static void createJar(@NotNull Path resDirectory, @NotNull Path jarFile) throws IOException {
    FrameworkResourceRepository repository = FrameworkResourceRepository.create(resDirectory, null, null, false);
    Set<String> languages = repository.getLanguageGroups();

    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jarFile))) {
      for (String language : languages) {
        String entryName = FrameworkResourceRepository.getResourceTableNameForLanguage(language);
        createZipEntry(entryName, getEncodedResources(repository, language), zip);
      }

      Path parentDir = resDirectory.getParent();
      List<Path> files = getContainedFiles(resDirectory);

      for (Path file : files) {
        // When running on Windows, we need to make sure that the file entries are correctly encoded
        // with the Unix path separator since the ZIP file spec only allows for that one.
        String relativePath = FileUtil.toSystemIndependentName(parentDir.relativize(file).toString());
        if (!relativePath.equals("res/version") && !relativePath.equals("res/BUILD")) { // Skip "version" and "BUILD" files.
          createZipEntry(relativePath, Files.readAllBytes(file), zip);
        }
      }
    }
  }

  @NotNull
  private static List<Path> getContainedFiles(@NotNull Path resDirectory) throws IOException {
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(resDirectory, new SimpleFileVisitor<Path>() {
      @Override
      @NotNull
      public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
        files.add(file);
        return FileVisitResult.CONTINUE;
      }
    });
    Collections.sort(files); // Make sure that the files are in canonical order.
    return files;
  }

  private static void createZipEntry(@NotNull String name, @NotNull byte[] content, @NotNull ZipOutputStream zip) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }

  @NotNull
  private static byte[] getEncodedResources(@NotNull FrameworkResourceRepository repository, @NotNull String language) throws IOException {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    try (Base128OutputStream stream = new Base128OutputStream(byteStream)) {
      repository.writeToStream(stream, config -> language.equals(FrameworkResourceRepository.getLanguageGroup(config)));
    }
    return byteStream.toByteArray();
  }

  private static void printUsage(@NotNull String programName) {
    System.out.printf("Usage: %s <res_directory> <jar_file>%n", programName);
  }
}