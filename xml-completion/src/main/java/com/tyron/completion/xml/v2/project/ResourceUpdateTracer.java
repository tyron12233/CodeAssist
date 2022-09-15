package com.tyron.completion.xml.v2.project;

import static com.android.ide.common.util.PathStringUtil.toPathString;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.Math.max;

import com.android.ide.common.util.PathString;
import com.android.utils.FlightRecorder;
import com.android.utils.TraceUtils;
import com.google.common.base.Joiner;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;

/**
 * Used to investigate b/167583128.
 */
public class ResourceUpdateTracer {
  private static boolean enabled;
  private static final Logger LOG = Logger.getInstance(ResourceUpdateTracer.class);

  static void startTracing() {
    FlightRecorder.initialize(300);
    enabled = true;
  }

  static void stopTracing() {
    enabled = false;
  }

  public static boolean isTracingActive() {
    return enabled;
  }

  public static void dumpTrace(@Nullable String message) {
    List<Object> trace = FlightRecorder.getAndClear();
    if (trace.isEmpty()) {
      if (message == null) {
        LOG.info("No resource updates recorded");
      }
      else {
        LOG.info(message + " - no resource updates recorded");
      }
    }
    else {
      String intro = isNullOrEmpty(message) ? "" : message + '\n';
      LOG.info(intro + "--- Resource update trace: ---\n" + Joiner.on('\n').join(trace) + "\n------------------------------");
    }
  }

  public static void log(@NotNull Supplier<?> lazyRecord) {
    if (enabled) {
      FlightRecorder.log(() -> TraceUtils.currentTime() + ' ' + lazyRecord.get());
    }
  }

  public static void logDirect(@NotNull Supplier<?> lazyRecord) {
    if (enabled) {
      LOG.info(lazyRecord.get().toString());
    }
  }

  public static @Nullable String pathForLogging(@Nullable File file) {
    if (file == null) {
      return null;
    }
    PathString path = toPathString(file);
    return path.subpath(max(path.getNameCount() - 6, 0), path.getNameCount()).getNativePath();
  }

//  public static @Nullable String pathForLogging(@Nullable PsiFile file) {
//    return file == null ? null : pathForLogging(file.getVirtualFile());
//  }

//  public static @Nullable String pathForLogging(@Nullable VirtualFile file, @NotNull Project project) {
//    if (file == null) {
//      return null;
//    }
//    return pathForLogging(FileExtensions.toPathString(file), project);
//  }

//  public static @NotNull String pathForLogging(@NotNull PathString file, @NotNull Project project) {
//    VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
//    if (projectDir == null) {
//      return file.subpath(max(file.getNameCount() - 4, 0), file.getNameCount()).getNativePath();
//    }
//    return FileExtensions.toPathString(projectDir).relativize(file).getNativePath();
//  }
//
//  public static @NotNull String pathsForLogging(@NotNull Collection<? extends VirtualFile> files, @NotNull Project project) {
//    return files.stream().map(file -> pathForLogging(file, project)).collect(Collectors.joining(", "));
//  }
}
