package org.jetbrains.kotlin.com.intellij.openapi.vfs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.messages.Topic;

public interface VirtualFilePointerListener {
  Topic<VirtualFilePointerListener>
          TOPIC = Topic.create("VirtualFilePointer", VirtualFilePointerListener.class);

  default void beforeValidityChanged(@NonNull VirtualFilePointer[] pointers) {
  }

  default void validityChanged(@NonNull VirtualFilePointer[] pointers) {
  }
}