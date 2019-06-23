/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

import ScopeExclude from './ScopeExclude';

class ProjectExclude {
    #path = '';

    #reason = '';

    #comment = ''

    #scopes = [];

    constructor(obj) {
        if (obj instanceof Object) {
            if (obj.path) {
                this.path = obj.path;
            }

            if (obj.reason) {
                this.reason = obj.reason;
            }

            if (obj.comment) {
                this.comment = obj.comment;
            }

            if (obj.scopes) {
                this.scopes = obj.scopes;
            }
        }
    }

    get path() {
        return this.#path;
    }

    set path(val) {
        this.#path = val;
    }

    get reason() {
        return this.#reason;
    }

    set reason(val) {
        this.#reason = val;
    }

    get comment() {
        return this.#comment;
    }

    set comment(val) {
        this.#comment = val;
    }

    get scopes() {
        return this.#scopes;
    }

    set scopes(val) {
        for (let i = 0, len = val.length; i < len; i++) {
            this.#scopes.push(new ScopeExclude(val[i]));
        }
    }
}

export default ProjectExclude;
