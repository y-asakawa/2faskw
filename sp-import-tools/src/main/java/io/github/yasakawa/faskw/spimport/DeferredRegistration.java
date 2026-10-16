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

import java.util.List;
import java.util.Set;

/** A registration-only view; the canonical bundle and its source diagnostics stay unchanged. */
final class DeferredRegistration {
    static Analysis view(Analysis source) throws ImportException {
        if (!source.mode.equals("shibboleth"))
            throw new ImportException(2, "--defer-attributes is only available for generic Shibboleth bundles; managed policy migration is unchanged");
        Analysis result = new Analysis();
        result.source = source.source;
        result.mode = source.mode;
        result.globalMfa = source.globalMfa;
        result.diagnostics.addAll(source.diagnostics);
        result.evidence.addAll(source.evidence);
        result.inputHashes.putAll(source.inputHashes);
        result.metadataSources.putAll(source.metadataSources);
        for (Candidate original : source.candidates) {
            Candidate c = new Candidate(original.entity, original.source, original.provider);
            c.name = original.name; c.hash = original.hash; c.metadata = original.metadata;
            c.sourceMetadataHash = original.sourceMetadataHash;
            c.bytes = original.bytes == null ? null : original.bytes.clone();
            c.managed = original.managed; c.access = original.access;
            c.attributesDeferred = true;
            c.attributes.addAll(original.attributes);
            c.releasePolicies.addAll(original.releasePolicies);
            c.facts.addAll(original.facts);
            c.facts.add("deferred_attributes=true; source_registration_state=" + original.registrationState()
                + "; profile=none; original attribute conditions remain pending, not resolved");
            for (Diagnostic d : original.diagnostics) {
                boolean defer = !source.diagnostics.contains(d)
                    && Set.of("ATTRIBUTE_CONDITION_UNSUPPORTED", "ATTRIBUTE_POLICY_UNRESOLVED").contains(d.code())
                    && Set.of("BLOCK", "ERROR").contains(d.severity());
                if (defer) {
                    c.diagnostic("REVIEW", d.code(), d.location(), d.detail(), d.remedy());
                    c.facts.add("deferred_original_diagnostic=" + d);
                } else c.diagnostics.add(d);
            }
            result.candidates.add(c);
        }
        return result;
    }

    static String todo(List<ExistingCli.Plan> plans) {
        StringBuilder out = new StringBuilder("Attribute migration: PENDING / 属性移行は未完了\n"
            + "Registration profile is fixed to none. No attributes approved; no profiles created; no authorization rules migrated.\n"
            + "IMPORTANT: none does NOT suppress shared target attribute-filter policies. Keep target isolated/unpublished.\n"
            + "Do not treat the discovered attribute list as exhaustive when policies are unresolved.\n");
        for (ExistingCli.Plan p : plans) {
            Candidate c = p.candidate();
            out.append("\nSP ").append(SafeFiles.display(c.entity)).append("\nname=").append(p.name())
                .append("\nsource_attributes=").append(c.attributes).append('\n');
            for (ReleasePolicy policy : c.releasePolicies) out.append("source_policy=").append(SafeFiles.display(policy.toString())).append('\n');
            for (String fact : c.facts) out.append("source_fact=").append(SafeFiles.display(fact)).append('\n');
            for (Diagnostic d : c.diagnostics) out.append(d);
            out.append(MigrationBundle.accessDetail(c));
            out.append("Next: review source value/deny/conditional rules; configure target resolver and SAML mapping; approve attributes once with an agreed classification/purpose; create/reuse a reviewed profile; use set-attributes for this name.\n"
                + "Check filtered AACLI for matching/nonmatching/missing/mixed values and browser allow/deny, NameID, encryption, SP trust, SSO and MFA before ending isolation.\n");
        }
        return out.toString();
    }

    private DeferredRegistration() { }
}
