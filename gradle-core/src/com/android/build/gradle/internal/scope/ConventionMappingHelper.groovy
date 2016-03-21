/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.internal.scope

import com.android.annotations.NonNull
import org.gradle.api.Task

import java.util.concurrent.Callable

/**
 * Helper class to dynamically access conventionMapping of a task.
 */
class ConventionMappingHelper {
    static void map(@NonNull Task task, @NonNull String key, @NonNull Closure<?> value) {
        task.conventionMapping.map(key, value);
    }

    static void map(@NonNull Task task, @NonNull String key, @NonNull Callable<?> value) {
        task.conventionMapping.map(key, value);
    }
}