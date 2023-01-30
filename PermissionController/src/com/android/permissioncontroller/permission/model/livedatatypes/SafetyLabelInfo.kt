/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.permissioncontroller.permission.model.livedatatypes

import com.android.permission.safetylabel.SafetyLabel

/**
 * A wrapping class for [SafetyLabel] class that includes the install source package name
 *
 * @param safetyLabel The resulting [SafetyLabel], or null if none found
 * @param installSourcePackageName The package name of the install source for the APK and safety
 * label(usually the app store)
 */
class SafetyLabelInfo(val safetyLabel: SafetyLabel?, val installSourcePackageName: String?) {

    companion object {
        /** Default definition of unavailable or no safety label found */
        val UNAVAILABLE = SafetyLabelInfo(null, null)
    }
}
