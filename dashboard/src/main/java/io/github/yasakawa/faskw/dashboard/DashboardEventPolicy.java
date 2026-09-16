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

import java.util.HashSet;
import java.util.Set;

final class DashboardEventPolicy {

    enum EventClass {
        AUTHENTICATION,
        SELF_SERVICE,
        ADMIN_API,
        RESTRICTED_OTHER
    }

    enum RouteCategory {
        ALL,
        AUTHENTICATION,
        SELF_SERVICE,
        ADMIN_API
    }

    private static final Set<String> AUTHENTICATION_EVENTS = Set.of(
            "START", "CHALLENGE_CREATED", "VERIFY",
            "FORCE_SEQUENCE_CHANGE_START", "FORCE_SEQUENCE_CHANGE_SAVE");

    private static final Set<String> SELF_SERVICE_EVENTS = Set.of(
            "TOTP_REGISTER_START", "TOTP_REGISTER_VERIFY", "TOTP_REGISTER_CANCEL",
            "SELF_SERVICE_AUTH", "SELF_SERVICE_HANDOFF", "CHANGE_LDAP_AUTH", "CHANGE_START",
            "CHANGE_CHALLENGE_CREATED", "CHANGE_VERIFY", "CHANGE_CHOOSE_SEQUENCE",
            "CHANGE_CHOOSE_METHOD", "CHANGE_BACK_MENU", "CHANGE_SAVE", "CHANGE_METHOD_SAVE");

    private static final Set<String> OPERATIONAL_EVENTS;

    static {
        final Set<String> events = new HashSet<>(AUTHENTICATION_EVENTS);
        events.addAll(SELF_SERVICE_EVENTS);
        OPERATIONAL_EVENTS = Set.copyOf(events);
    }

    private DashboardEventPolicy() {
    }

    static DashboardEventScope scopeFor(final DashboardAuthorizer.Principal principal) {
        return DashboardEventScope.fromPrincipal(principal);
    }

    static EventClass classify(final String eventType) {
        if (eventType == null) {
            return EventClass.RESTRICTED_OTHER;
        }
        if (AUTHENTICATION_EVENTS.contains(eventType)) {
            return EventClass.AUTHENTICATION;
        }
        if (SELF_SERVICE_EVENTS.contains(eventType)) {
            return EventClass.SELF_SERVICE;
        }
        if (eventType.startsWith("API_")) {
            return EventClass.ADMIN_API;
        }
        return EventClass.RESTRICTED_OTHER;
    }

    static Set<String> operationalEvents() {
        return OPERATIONAL_EVENTS;
    }

    static Set<String> eventsFor(final RouteCategory category) {
        return switch (category) {
            case AUTHENTICATION -> AUTHENTICATION_EVENTS;
            case SELF_SERVICE -> SELF_SERVICE_EVENTS;
            case ALL, ADMIN_API -> Set.of();
        };
    }
}
