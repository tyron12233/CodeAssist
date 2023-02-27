package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.PathManager;
import org.jetbrains.kotlin.com.intellij.util.io.DataInputOutputUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

final class PersistentIndicesConfiguration {
  private static final int BASE_INDICES_CONFIGURATION_VERSION = 1;

  static void saveConfiguration() {
    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indicesConfigurationFile())))) {
      DataInputOutputUtil.writeINT(out, BASE_INDICES_CONFIGURATION_VERSION);
      IndexVersion.savePersistentIndexStamp(out);
    }
    catch (IOException ignored) {
    }
  }

  static void loadConfiguration() {
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(indicesConfigurationFile())))) {
      if (DataInputOutputUtil.readINT(in) == BASE_INDICES_CONFIGURATION_VERSION) {
        IndexVersion.initPersistentIndexStamp(in);
      }
    }
    catch (IOException ignored) {
    }
  }

  private static @NotNull Path indicesConfigurationFile() {
    return PathManager.getIndexRoot().toPath().resolve("indices.config");
  }
}