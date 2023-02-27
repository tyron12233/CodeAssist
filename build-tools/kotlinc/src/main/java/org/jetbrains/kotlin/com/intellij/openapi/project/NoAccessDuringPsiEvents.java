package org.jetbrains.kotlin.com.intellij.openapi.project;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.psi.impl.DebugUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBus;

import java.util.HashSet;
import java.util.Set;

public final class NoAccessDuringPsiEvents {
  private static final Logger LOG = Logger.getInstance(NoAccessDuringPsiEvents.class);
  private static final Set<String> ourReportedTraces = new HashSet<>();

  public static void checkCallContext(@NotNull ID<?, ?> indexId) {
    checkCallContext("access index #" + indexId.getName());
  }

  public static void checkCallContext(@NotNull String contextDescription) {
    if (isInsideEventProcessing() && ourReportedTraces.add(DebugUtil.currentStackTrace())) {
      LOG.error("It's prohibited to " + contextDescription + " during event dispatching");
    }
  }

  public static boolean isInsideEventProcessing() {
    Application application = ApplicationManager.getApplication();
      if (!application.isWriteAccessAllowed()) {
          return false;
      }

//    MessageBus bus = application.getMessageBus();
//    return bus.hasUndeliveredEvents(VirtualFileManager.VFS_CHANGES) ||
//           bus.hasUndeliveredEvents(PsiModificationTracker.TOPIC) ||
//           bus.hasUndeliveredEvents(ProjectTopics.PROJECT_ROOTS) ||
//           bus.hasUndeliveredEvents(AdditionalLibraryRootsListener.TOPIC);
    throw new UnsupportedOperationException();
  }
}