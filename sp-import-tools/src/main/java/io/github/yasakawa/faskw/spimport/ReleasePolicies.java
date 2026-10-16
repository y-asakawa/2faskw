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

package io.github.yasakawa.faskw.spimport;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Separates the CLI-generated SP policy from other statically resolved releases. */
final class ReleasePolicies {
    static Set<String> additional(ImportModel.Candidate c, String name, Set<String> profile, boolean required) {
        String id = "2faskw-" + name;
        var own = c.releasePolicies.stream().filter(p -> p.id().equals(id)).toList();
        if (own.size() > 1 || (required && !profile.isEmpty() && own.isEmpty()))
            throw new IllegalArgumentException("SOURCE_ATTRIBUTE_FILTER_DRIFT: missing/duplicate SP policy " + id);
        if (!own.isEmpty() && (!own.getFirst().managedShape() || !own.getFirst().attributes().equals(profile)))
            throw new IllegalArgumentException("SOURCE_ATTRIBUTE_FILTER_DRIFT: SP policy " + id + " expected=" + profile
                + " actual=" + own.getFirst().attributes() + " at " + own.getFirst().location());
        Set<String> extra = new TreeSet<>();
        for (var policy : c.releasePolicies) if (!policy.id().equals(id)) {
            if (policy.id().startsWith("2faskw-") && !policy.attributes().isEmpty())
                throw new IllegalArgumentException("SOURCE_ATTRIBUTE_FILTER_DRIFT: another managed SP policy applies: " + policy.id());
            extra.addAll(policy.attributes());
        }
        extra.removeAll(profile);
        return extra;
    }

    static ManagedData.ReleaseContract source(ImportModel.Candidate c, String name, List<String> profile) {
        Set<String> expected = new TreeSet<>(profile);
        var extra = additional(c, name, expected, true);
        if (extra.size() > 256) throw new IllegalArgumentException("SOURCE_ATTRIBUTE_FILTER_DRIFT: more than 256 additional attributes");
        Set<String> union = new TreeSet<>(expected); union.addAll(extra);
        if (!union.equals(c.attributes)) throw new IllegalArgumentException("SOURCE_ATTRIBUTE_FILTER_DRIFT: unresolved release composition");
        return new ManagedData.ReleaseContract(List.copyOf(expected), List.copyOf(extra));
    }
    private ReleasePolicies() { }
}
