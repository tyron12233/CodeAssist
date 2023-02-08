package org.jetbrains.kotlin.com.intellij.lang;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.extensions.CustomLoadingExtensionPointBean;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstance;
import org.jetbrains.kotlin.com.intellij.util.xmlb.annotations.Attribute;

/**
 * Base class for {@link Language}-bound extension points.
 */
public class LanguageExtensionPoint<T> extends CustomLoadingExtensionPointBean<T> implements KeyedLazyInstance<T> {
  // these must be public for scrambling compatibility
  /**
   * Language ID.
   *
   * @see Language#getID()
   */
  @Attribute("language")
  public String language;

  @Attribute("implementationClass")
  public String implementationClass;

  /**
   * @deprecated You must pass plugin descriptor, use {@link LanguageExtensionPoint#LanguageExtensionPoint(String, Object)}
   */
  @Deprecated
  public LanguageExtensionPoint() {
  }

  public LanguageExtensionPoint(@NonNull String language, @NonNull String implementationClass, @NonNull PluginDescriptor pluginDescriptor) {
    this.language = language;
    this.implementationClass = implementationClass;
    setPluginDescriptor(pluginDescriptor);
  }

  public LanguageExtensionPoint(@NonNull String language, @NonNull T instance) {
    super(instance);

    this.language = language;
    implementationClass = instance.getClass().getName();
  }

  @Nullable
  @Override
  protected final String getImplementationClassName() {
    return implementationClass;
  }

  @Override
  public String getKey() {
    return language;
  }
}