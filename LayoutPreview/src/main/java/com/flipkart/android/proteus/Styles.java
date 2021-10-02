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

package com.flipkart.android.proteus;

import com.flipkart.android.proteus.value.Value;

import java.util.HashMap;
import java.util.Map;

/**
 * Styles
 *
 * @author Aditya Sharat
 */
public class Styles extends HashMap<String, Map<String, Value>> {

  public Map<String, Value> getStyle(String name) {
    return this.get(name);
  }

  public boolean contains(String name) {
    return this.containsKey(name);
  }
}
