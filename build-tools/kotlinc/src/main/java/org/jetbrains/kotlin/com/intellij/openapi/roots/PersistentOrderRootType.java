package org.jetbrains.kotlin.com.intellij.openapi.roots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;

public class PersistentOrderRootType extends OrderRootType {
  private final String mySdkRootName;
  private final String myModulePathsName;
  private final String myOldSdkRootName;

  protected PersistentOrderRootType(@NonNull String name, @Nullable String sdkRootName, @Nullable String modulePathsName, @Nullable final String oldSdkRootName) {
    super(name);
    mySdkRootName = sdkRootName;
    myModulePathsName = modulePathsName;
    myOldSdkRootName = oldSdkRootName;
    //noinspection AssignmentToStaticFieldFromInstanceMethod
    ourPersistentOrderRootTypes = ArrayUtil.append(ourPersistentOrderRootTypes, this);
  }

  /**
   * @return Element name used for storing roots of this type in JDK definitions.
   */
  @Nullable
  public String getSdkRootName() {
    return mySdkRootName;
  }

  @Nullable
  public String getOldSdkRootName() {
    return myOldSdkRootName;
  }

  /**
   * @return Element name used for storing roots of this type in module definitions.
   */
  @Nullable
  public String getModulePathsName() {
    return myModulePathsName;
  }
}