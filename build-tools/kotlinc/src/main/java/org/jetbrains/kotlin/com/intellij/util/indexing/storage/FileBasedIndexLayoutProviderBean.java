package org.jetbrains.kotlin.com.intellij.util.indexing.storage;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.AbstractBundle;
import org.jetbrains.kotlin.com.intellij.DynamicBundle;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginAware;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.util.xmlb.annotations.Attribute;

import java.util.ResourceBundle;

@ApiStatus.Internal
public final class FileBasedIndexLayoutProviderBean implements PluginAware {
  /**
   * A class which implements {@link FileBasedIndexLayoutProvider}
   */
//  @RequiredElement
  @Attribute("providerClass")
  public String providerClass;

  /**
   * Unique storage id which is supposed to be used only for debug reasons
   */
//  @RequiredElement
  @Attribute("id")
  public String id;

  /**
   * A property key which refers to storage presentable name
   */
//  @RequiredElement
  @NonNls
  @Attribute("presentableNameKey")
  public String presentableNameKey;

  /**
   * A bundle name to find presentable name key
   */
  @NonNls
  @Attribute("bundleName")
  public String bundleName;

  /**
   * Version of provided storage
   */
//  @RequiredElement
  @NonNls
  @Attribute("version")
  public int version;

  @SuppressWarnings("HardCodedStringLiteral")
  public @NotNull String getLocalizedPresentableName() {
//    String resourceBundleBaseName = bundleName != null ? bundleName : myPluginDescriptor.getResourceBundleBaseName();
//    if (resourceBundleBaseName == null) {
//      Logger.getInstance(FileBasedIndexLayoutProviderBean.class).error("Can't find resource bundle name for " + myPluginDescriptor.getName());
//      return "!" + presentableNameKey + "!";
//    }
//    ResourceBundle resourceBundle = DynamicBundle.INSTANCE.getResourceBundle(myPluginDescriptor.getPluginClassLoader());
//    return AbstractBundle.message(resourceBundle, presentableNameKey);
    throw new UnsupportedOperationException();
  }

  private FileBasedIndexLayoutProvider myLayoutProvider;
  public @NotNull synchronized FileBasedIndexLayoutProvider getLayoutProvider() {
    if (myLayoutProvider == null) {
      try {
        myLayoutProvider = (FileBasedIndexLayoutProvider) ApplicationManager.getApplication().instantiateClass(Class.forName(providerClass), myPluginDescriptor.getPluginId());
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
    return myLayoutProvider;
  }

  private volatile PluginDescriptor myPluginDescriptor;
  @Override
  public void setPluginDescriptor(@NotNull PluginDescriptor pluginDescriptor) {
    myPluginDescriptor = pluginDescriptor;
  }
}
