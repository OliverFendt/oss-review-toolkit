/*
 * Copyright (C) 2019 Bosch Software Innovations GmbH
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
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.analyzer.curation

import com.here.ort.analyzer.PackageCurationProvider
import com.here.ort.model.Identifier

/**
 * A curation provider that does not provide any curations on its own, but searches the given list of [providers] for
 * the first matching curation, and falls back to the next provider in the list if there is no match.
 */
class FallbackPackageCurationProvider(private val providers: List<PackageCurationProvider>) : PackageCurationProvider {
    override fun getCurationsFor(pkgId: Identifier) =
        providers.asSequence().map { it.getCurationsFor(pkgId) }.find { it.isNotEmpty() }.orEmpty()
}
