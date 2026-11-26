// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController =
    remember {
        object : NotificationPermissionController {
            override fun status() = NotificationPermissionStatus.NOT_APPLICABLE
            override fun request(onResult: (Boolean) -> Unit) = onResult(true)
            override fun openSettings() {}
        }
    }
