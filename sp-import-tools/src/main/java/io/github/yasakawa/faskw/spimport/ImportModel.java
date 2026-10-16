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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

final class ImportModel {
    record AccessAssessment(String status, List<String> evidence) {
        AccessAssessment {
            if (!java.util.Set.of("NONE_OBSERVED", "MANUAL_REQUIRED", "UNRESOLVED", "LEGACY_UNASSESSED").contains(status))
                throw new IllegalArgumentException("invalid access assessment status");
            if (evidence.size() > 1000) throw new IllegalArgumentException("access assessment evidence limit exceeded");
            evidence = evidence.stream().map(SafeFiles::display).toList();
        }
        boolean pending() { return !status.equals("NONE_OBSERVED"); }
    }
    record ReleasePolicy(String id, boolean managedShape, java.util.Set<String> attributes, String location) { }
    record Diagnostic(String severity, String code, String location, String detail, String remedy) {
        String reason() {
            if (!detail.startsWith("reason=")) return "";
            int end = detail.indexOf(';');
            if (end < 7) return "";
            String value = detail.substring(7, end);
            return value.matches("[A-Z][A-Z0-9_]{0,95}") ? value : "";
        }
        @Override public String toString() {
            return severity + " " + code + " at " + SafeFiles.display(location) + "\n  cause: "
                + SafeFiles.display(detail) + "\n  action: " + SafeFiles.display(remedy) + "\n";
        }
    }

    static final class Candidate {
        final String entity;
        final String source;
        final String provider;
        String name;
        String hash = "";
        String metadata = "";
        String sourceMetadataHash = "";
        byte[] bytes;
        ManagedData.Intent managed;
        AccessAssessment access;
        boolean attributesDeferred;
        final TreeSet<String> attributes = new TreeSet<>();
        final List<ReleasePolicy> releasePolicies = new ArrayList<>();
        final List<String> facts = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();

        Candidate(String entity, String source, String provider) {
            this.entity = entity;
            this.source = source;
            this.provider = provider;
            name = "import-" + SafeFiles.hash(entity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12);
        }

        boolean blocked() { return diagnostics.stream().anyMatch(d -> "BLOCK".equals(d.severity()) || "ERROR".equals(d.severity())); }
        String state() { return blocked() ? "HELD / 移行保留" : "REVIEW / 確認が必要"; }
        String registrationState() { return blocked() ? "HELD" : "REVIEW"; }
        String accessState() { return access == null ? managed != null ? "MANAGED_POLICY_WORKFLOW" : "NOT_ASSESSED" : access.status(); }
        boolean accessPending() { return access != null && access.pending(); }
        boolean metadataReviewRequired() { return diagnostics.stream().anyMatch(d -> java.util.Set.of(
            "METADATA_AGGREGATE_EXTRACTED", "METADATA_SIGNATURE_VALIDATION_REQUIRED", "METADATA_VALIDITY_FILTER_REVIEW_REQUIRED", "METADATA_ROLE_FILTER_REVIEW_REQUIRED").contains(d.code())); }
        void diagnostic(String severity, String code, String location, String detail, String remedy) {
            diagnostics.add(new Diagnostic(severity, code, location, detail, remedy));
        }
    }

    static final class Analysis {
        final List<Candidate> candidates = new ArrayList<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<String> evidence = new ArrayList<>();
        Path source;
        String mode = "shibboleth";
        java.util.Map<String, String> globalMfa = java.util.Map.of();
        final java.util.Map<Path, String> inputHashes = new java.util.LinkedHashMap<>();
        final java.util.Map<String, byte[]> metadataSources = new java.util.LinkedHashMap<>();
        boolean held() { return diagnostics.stream().anyMatch(d -> "BLOCK".equals(d.severity()) || "ERROR".equals(d.severity()))
            || candidates.stream().anyMatch(Candidate::blocked); }
        boolean accessPending() { return candidates.stream().anyMatch(Candidate::accessPending); }
    }

    static final class ImportException extends Exception {
        final int exitCode;
        ImportException(int code, String message) { super(message); exitCode = code; }
    }

    private ImportModel() { }
}
