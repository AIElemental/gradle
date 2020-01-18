/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.api.internal.changedetection.state;

import org.gradle.internal.hash.Hasher;

/**
 * A resource entry filter supporting exact matches of values.
 */
public interface ResourceEntryFilter extends ConfigurableNormalizer {
    ResourceEntryFilter FILTER_NOTHING = new ResourceEntryFilter() {
        @Override
        public boolean shouldBeIgnored(String value) {
            return false;
        }

        @Override
        public void appendConfigurationToHasher(Hasher hasher) {
            hasher.putString(getClass().getName());
        }
    };

    boolean shouldBeIgnored(String value);
}
