/*
 * Copyright 2019 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flipkart.android.proteus.value;

import com.flipkart.android.proteus.ProteusConstants;

/**
 * Null
 *
 * @author aditya.sharat
 */

public class Null extends Value {
  /**
   * singleton for JsonNull
   *
   * @since 1.8
   */
  public static final Null INSTANCE = new Null();

  private static final String NULL_STRING = "NULL";

  @Override
  public Null copy() {
    return INSTANCE;
  }

  @Override
  public String toString() {
    return NULL_STRING;
  }

  @Override
  public String getAsString() {
    return ProteusConstants.EMPTY;
  }

  /**
   * All instances of Null have the same hash code
   * since they are indistinguishable
   */
  @Override
  public int hashCode() {
    return Null.class.hashCode();
  }

  /**
   * All instances of JsonNull are the same
   */
  @Override
  public boolean equals(java.lang.Object other) {
    return this == other || other instanceof Null;
  }
}
