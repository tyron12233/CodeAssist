package com.tyron.completion.xml.v2.base;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** A filter used to select files when traversing the file system. */
interface FileFilter {
  /** Returns true to skip the file or directory, or false to accept it. */
  boolean isIgnored(@NotNull Path fileOrDirectory, @NotNull BasicFileAttributes attrs);
}