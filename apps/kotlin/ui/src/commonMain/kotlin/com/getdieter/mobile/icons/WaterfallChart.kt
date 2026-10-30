/*
 * Copyright 2024 The Android Open Source Project
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

package com.getdieter.mobile.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.WaterfallChart: ImageVector
    get() {
        if (_waterfallChart != null) {
            return _waterfallChart!!
        }
        _waterfallChart =
            materialIcon(name = "Outlined.WaterfallChart") {
                materialPath {
                    moveTo(18.0f, 4.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(16.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineTo(4.0f)
                    close()
                    moveTo(3.0f, 13.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(7.0f)
                    horizontalLineTo(3.0f)
                    verticalLineTo(13.0f)
                    close()
                    moveTo(14.0f, 4.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(3.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineTo(4.0f)
                    close()
                    moveTo(10.0f, 5.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(-3.0f)
                    verticalLineTo(5.0f)
                    close()
                    moveTo(7.0f, 10.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineTo(7.0f)
                    verticalLineTo(10.0f)
                    close()
                }
            }
        return _waterfallChart!!
    }

private var _waterfallChart: ImageVector? = null
