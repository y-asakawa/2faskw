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

import static io.github.yasakawa.faskw.spimport.ImportModel.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/** Recheck the frozen snapshot, not a remote URL, after interactive review and before writes. */
final class MetadataValidity {
    static void check(List<Candidate> candidates, Instant now) throws Exception {
        for (Candidate c : candidates) {
            if (c.bytes == null) throw new ImportException(6, "SOURCE_METADATA_INVALID: missing snapshot for " + SafeFiles.display(c.entity));
            SecureXml.Node root = SecureXml.parse(c.bytes, c.source);
            if (!root.namespace.equals(SecureXml.MD) || !root.name.equals("EntityDescriptor") || !root.attr("entityID").equals(c.entity))
                throw new ImportException(6, "SOURCE_METADATA_INVALID: snapshot entityID/root mismatch: " + SafeFiles.display(c.entity));
            for (SecureXml.Node node : root.descendants()) {
                if (!node.namespace.equals(SecureXml.MD) || !Set.of("EntityDescriptor", "SPSSODescriptor", "RoleDescriptor").contains(node.name)) continue;
                String validity = node.attr("validUntil");
                if (validity.isEmpty()) continue;
                try {
                    if (Instant.parse(validity).isAfter(now)) continue;
                } catch (DateTimeParseException ex) {
                    throw new ImportException(6, "SOURCE_METADATA_INVALID: malformed validUntil=" + SafeFiles.display(validity) + " at " + SafeFiles.display(node.location));
                }
                throw new ImportException(5, "SOURCE_METADATA_EXPIRED: entity=" + SafeFiles.display(c.entity)
                    + " validUntil=" + validity + " checked_at=" + now + " at " + SafeFiles.display(node.location)
                    + "; obtain trusted current metadata and export a new bundle; static snapshots do not auto-refresh");
            }
        }
    }
    private MetadataValidity() { }
}
