/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.api.internal.changedetection.state.mirror;

import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hashing;

import javax.annotation.Nullable;

public interface PhysicalDirectorySnapshot extends PhysicalSnapshot {
    HashCode SIGNATURE = Hashing.md5().hashString(PhysicalDirectorySnapshot.class.getName());

    /**
     * The tree hash of a directory is the combined hash of all its children.
     */
    @Nullable
    HashCode getTreeHash();
}