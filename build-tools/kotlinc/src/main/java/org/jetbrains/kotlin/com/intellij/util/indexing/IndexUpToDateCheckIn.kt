package org.jetbrains.kotlin.com.intellij.util.indexing

import org.jetbrains.kotlin.com.intellij.openapi.util.ThrowableComputable

object IndexUpToDateCheckIn {
  private val upToDateCheckState = ThreadLocal<Int>()

  @JvmStatic
  fun <T, E : Throwable?> disableUpToDateCheckIn(runnable: ThrowableComputable<T, E>): T {
    disableUpToDateCheckForCurrentThread()
    return try {
      runnable.compute()
    }
    finally {
      enableUpToDateCheckForCurrentThread()
    }
  }

  @JvmStatic
  fun isUpToDateCheckEnabled(): Boolean {
    val value: Int? = upToDateCheckState.get()
    return value == null || value == 0
  }

  private fun disableUpToDateCheckForCurrentThread() {
    val currentValue = upToDateCheckState.get()
    upToDateCheckState.set(if (currentValue == null) 1 else currentValue.toInt() + 1)
  }

  private fun enableUpToDateCheckForCurrentThread() {
    val currentValue = upToDateCheckState.get()
    if (currentValue != null) {
      val newValue = currentValue.toInt() - 1
      if (newValue != 0) {
        upToDateCheckState.set(newValue)
      }
      else {
        upToDateCheckState.remove()
      }
    }
  }
}