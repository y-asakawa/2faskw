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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GraphicalMatrixSpAccessEvaluator {
    enum Result {
        ALLOW,
        DENY,
        NOT_APPLICABLE
    }

    record Decision(Result result, String reason, Set<String> attributesChecked) {
        Decision {
            attributesChecked = Set.copyOf(attributesChecked);
        }
    }

    Decision evaluate(final GraphicalMatrixSpAccessPolicy.Policy policy,
            final String entityId, final Map<String, List<String>> attributes) {
        if (policy == null || !policy.enabled()) {
            return new Decision(Result.NOT_APPLICABLE,
                policy == null ? "POLICY_NOT_CONFIGURED" : "POLICY_DISABLED", Set.of());
        }
        if (!policy.entityId().equals(entityId)) {
            return new Decision(Result.DENY, "POLICY_IDENTITY_MISMATCH", Set.of());
        }
        final Set<String> checked = new LinkedHashSet<>();
        for (final GraphicalMatrixSpAccessPolicy.Rule rule : policy.deny()) {
            checked.add(rule.attributeId());
            if (matches(rule, attributes.get(rule.attributeId()))) {
                return new Decision(Result.DENY, "EXPLICIT_DENY", checked);
            }
        }
        for (final GraphicalMatrixSpAccessPolicy.Rule rule : policy.allow()) {
            checked.add(rule.attributeId());
            final List<String> actual = attributes.get(rule.attributeId());
            if (actual == null || actual.isEmpty()) {
                return new Decision(Result.DENY, "ATTRIBUTE_MISSING", checked);
            }
            if (!matches(rule, actual)) {
                return new Decision(Result.DENY, "ATTRIBUTE_VALUE_MISMATCH", checked);
            }
        }
        return new Decision(Result.ALLOW, "ALL_ALLOW_CONDITIONS_MATCHED", checked);
    }

    private static boolean matches(final GraphicalMatrixSpAccessPolicy.Rule rule,
            final List<String> actual) {
        if (actual == null) {
            return false;
        }
        for (final String value : actual) {
            if (rule.values().contains(value)) {
                return true;
            }
        }
        return false;
    }
}
