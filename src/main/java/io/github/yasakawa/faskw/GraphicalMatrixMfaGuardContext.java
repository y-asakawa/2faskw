/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.yasakawa.faskw;

import org.opensaml.messaging.context.BaseContext;

/** Request-scoped state binding an MFA decision to its completion check. */
final class GraphicalMatrixMfaGuardContext extends BaseContext {
    private final String user;
    private final String expectedFlow;
    private final String expectedMethod;
    private final long stateVersion;

    GraphicalMatrixMfaGuardContext(final String user, final String expectedFlow,
            final String expectedMethod, final long stateVersion) {
        this.user = user;
        this.expectedFlow = expectedFlow;
        this.expectedMethod = expectedMethod;
        this.stateVersion = stateVersion;
    }

    String user() {
        return user;
    }

    String expectedFlow() {
        return expectedFlow;
    }

    String expectedMethod() {
        return expectedMethod;
    }

    long stateVersion() {
        return stateVersion;
    }
}
