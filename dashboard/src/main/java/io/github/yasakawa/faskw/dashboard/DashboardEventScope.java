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
package io.github.yasakawa.faskw.dashboard;

import java.util.Objects;

final class DashboardEventScope {

    enum Kind {
        OPERATIONAL,
        ALL
    }

    private final Kind kind;

    private DashboardEventScope(final Kind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    static DashboardEventScope fromPrincipal(final DashboardAuthorizer.Principal principal) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(principal.role(), "principal.role");
        return switch (principal.role()) {
            case DASHBOARD_VIEWER, DASHBOARD_OPERATOR ->
                    new DashboardEventScope(Kind.OPERATIONAL);
            case DASHBOARD_AUDITOR, DASHBOARD_ADMIN ->
                    new DashboardEventScope(Kind.ALL);
        };
    }

    boolean allowsAllEvents() {
        return kind == Kind.ALL;
    }

    boolean allowsLockedUsers() {
        return kind == Kind.ALL;
    }

    boolean allows(final String eventType) {
        if (allowsAllEvents()) {
            return true;
        }
        final DashboardEventPolicy.EventClass eventClass =
                DashboardEventPolicy.classify(eventType);
        return eventClass == DashboardEventPolicy.EventClass.AUTHENTICATION
                || eventClass == DashboardEventPolicy.EventClass.SELF_SERVICE;
    }

    String externalName() {
        return kind.name();
    }
}
