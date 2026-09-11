/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.compose.ui.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system

@OptIn(ExperimentalForeignApi::class)
private class LinuxUriHandler : UriHandler {
    override fun openUri(uri: String) {
        if (uri.isEmpty() || uri.any { it == '\'' || it == '\n' || it == '\r' }) {
            return
        }
        val escaped = uri.replace("'", "'\\''")
        system("xdg-open '$escaped' >/dev/null 2>&1 &")
    }
}

internal actual fun createPlatformUriHandler(): UriHandler = LinuxUriHandler()
