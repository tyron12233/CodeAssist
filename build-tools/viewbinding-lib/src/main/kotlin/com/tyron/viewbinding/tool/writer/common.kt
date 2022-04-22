/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.viewbinding.tool.writer

import com.tyron.viewbinding.tool.store.ResourceBundle.BindingTargetBundle
import com.squareup.javapoet.ClassName

internal val ANDROID_VIEW: ClassName = ClassName.get("android.view", "View")
internal val ANDROID_LAYOUT_INFLATER: ClassName = ClassName.get("android.view", "LayoutInflater")
internal val ANDROID_VIEW_GROUP: ClassName = ClassName.get("android.view", "ViewGroup")

internal val BindingTargetBundle.fieldType: String get() = interfaceType ?: fullClassName

internal fun renderConfigurationJavadoc(present: List<String>, absent: List<String>): String {
    return """
        |This binding is not available in all configurations.
        |<p>
        |Present:
        |<ul>
        |${present.joinToString("\n|") { "  <li>$it/</li>" }}
        |</ul>
        |
        |Absent:
        |<ul>
        |${absent.joinToString("\n|") { "  <li>$it/</li>" }}
        |</ul>
        |""".trimMargin() // Trailing newline for JavaPoet.
}
