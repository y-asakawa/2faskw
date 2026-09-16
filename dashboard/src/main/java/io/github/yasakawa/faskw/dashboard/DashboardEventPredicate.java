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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class DashboardEventPredicate {

    private final String sql;
    private final List<String> parameters;

    private DashboardEventPredicate(final String sql, final List<String> parameters) {
        this.sql = sql;
        this.parameters = List.copyOf(parameters);
    }

    static DashboardEventPredicate create(
            final DashboardEventScope scope,
            final DashboardEventPolicy.RouteCategory category) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(category, "category");

        final List<String> clauses = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        if (!scope.allowsAllEvents()) {
            appendInClause(clauses, values, DashboardEventPolicy.operationalEvents());
        }
        switch (category) {
            case ALL -> {
                // The Principal-derived scope is the complete route restriction.
            }
            case AUTHENTICATION, SELF_SERVICE ->
                    appendInClause(clauses, values, DashboardEventPolicy.eventsFor(category));
            case ADMIN_API -> clauses.add("LEFT(event_type, 4) = 'API_'");
        }
        if (clauses.isEmpty()) {
            return new DashboardEventPredicate("", List.of());
        }
        return new DashboardEventPredicate(
                " AND (" + String.join(") AND (", clauses) + ")", values);
    }

    void appendTo(final StringBuilder target) {
        target.append(sql);
    }

    int bind(final PreparedStatement statement, final int firstParameter) throws SQLException {
        int parameter = firstParameter;
        for (String value : parameters) {
            statement.setString(parameter++, value);
        }
        return parameter;
    }

    private static void appendInClause(
            final List<String> clauses,
            final List<String> parameters,
            final Collection<String> allowedEvents) {
        if (allowedEvents.isEmpty()) {
            clauses.add("1 = 0");
            return;
        }
        final List<String> sorted = allowedEvents.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        clauses.add("event_type IN (" + "?, ".repeat(sorted.size() - 1) + "?)");
        parameters.addAll(sorted);
    }
}
