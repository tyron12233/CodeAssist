package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.util.SimpleModificationTracker;

@ApiStatus.Internal
public class IndexableSetContributorModificationTracker extends SimpleModificationTracker {
  public static IndexableSetContributorModificationTracker getInstance() {
    return ApplicationManager.getApplication().getService(IndexableSetContributorModificationTracker.class);
  }

  public IndexableSetContributorModificationTracker() {
    IndexableSetContributor.EP_NAME.addChangeListener(this::incModificationCount, ApplicationManager.getApplication());
  }
}