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

import java.nio.file.Path;
import java.util.List;

/** Recognizes only static ContextCheck wiring; never executes Spring configuration. */
final class ManagedAccessWiring {
    static void require(Path root) throws Exception {
        var context = SecureXml.parse(SafeFiles.read(SafeFiles.contained(root, "conf/intercept/context-check-intercept-config.xml"), 2 * 1024 * 1024), "context-check");
        var functions = context.descendants("bean").stream().filter(n -> n.namespace.equals(SecureXml.SPRING) && n.attr("id").equals("shibboleth.context-check.Function")).toList();
        if (functions.size() != 1 || !functions.getFirst().attr("class").equals("io.github.yasakawa.faskw.GraphicalMatrixSpAccessFunction")
                || context.descendants("bean").stream().anyMatch(n -> n.attr("id").equals("shibboleth.context-check.Condition"))) throw new IllegalArgumentException("SOURCE_ACCESS_WIRING_UNSUPPORTED: function/condition");
        var rp = SecureXml.parse(SafeFiles.read(SafeFiles.contained(root, "conf/relying-party.xml"), 2 * 1024 * 1024), "relying-party");
        int profiles = 0;
        for (var n : rp.descendants()) {
            if (!n.namespace.equals(SecureXml.SPRING)) continue;
            if (n.name.equals("ref") && n.attr("bean").equals("SAML2.SSO")) throw new IllegalArgumentException("SOURCE_ACCESS_WIRING_UNSUPPORTED: plain SAML2.SSO reference");
            if (n.name.equals("bean") && n.attr("parent").contains("SAML2.SSO")) {
                profiles++;
                var properties = n.children.stream().filter(c -> c.namespace.equals(SecureXml.SPRING) && c.name.equals("property") && c.attr("name").equals("postAuthenticationFlows")).toList();
                List<String> flows = List.of();
                String attribute = n.attr("{http://www.springframework.org/schema/p}postAuthenticationFlows");
                if (!attribute.isBlank()) flows = List.of(attribute.split("[,\\s]+"));
                if (!properties.isEmpty()) {
                    if (!flows.isEmpty() || properties.size() != 1 || properties.getFirst().children.size() != 1 || !properties.getFirst().children.getFirst().name.equals("list")) throw new IllegalArgumentException("SOURCE_ACCESS_WIRING_UNSUPPORTED: flow list");
                    var list = properties.getFirst().children.getFirst();
                    if (list.children.stream().anyMatch(c -> !c.namespace.equals(SecureXml.SPRING) || !c.name.equals("value"))) throw new IllegalArgumentException("SOURCE_ACCESS_WIRING_UNSUPPORTED: dynamic flow list");
                    flows = list.children.stream().map(c -> c.text.toString().trim()).toList();
                }
                if (flows.stream().filter("context-check"::equals).count() != 1) throw new IllegalArgumentException("SOURCE_ACCESS_WIRING_UNSUPPORTED: missing/duplicate context-check");
            }
        }
        if (profiles == 0) throw new IllegalArgumentException("SOURCE_ACCESS_WIRING_UNSUPPORTED: no SAML2 profile");
    }
    private ManagedAccessWiring() { }
}
