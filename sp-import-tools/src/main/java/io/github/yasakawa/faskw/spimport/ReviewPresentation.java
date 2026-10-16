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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Read-only guidance; registration eligibility and approval evidence remain unchanged. */
final class ReviewPresentation {
    private static final java.util.Set<String> VALIDITY_CODES = java.util.Set.of("METADATA_EFFECTIVE_EXPIRED", "METADATA_SOURCE_FILTER_REJECTED");
    private record Guidance(String causeJa, String actionJa, String causeEn, String actionEn) { }

    static String overview(Analysis analysis, boolean ja, String filter) {
        StringBuilder out = new StringBuilder("============================================================\n")
            .append(t(ja, "SP移行解析結果", "SP migration analysis")).append('\n')
            .append("============================================================\n");
        long held = analysis.candidates.stream().filter(Candidate::blocked).count();
        long pending = analysis.candidates.stream().filter(Candidate::accessPending).count();
        boolean deferred = analysis.candidates.stream().anyMatch(c -> c.attributesDeferred);
        out.append(t(ja, "検出したSP：", "Detected SPs: ")).append(analysis.candidates.size()).append('\n')
            .append(t(ja, "  確認後に登録可能：", "  Review before registration: ")).append(analysis.candidates.size() - held).append('\n')
            .append(t(ja, "  登録保留：", "  Held: ")).append(held).append('\n')
            .append(t(ja, "  元のアクセス制御が未移行：", "  Source authorization still pending: ")).append(pending).append('\n')
            .append(t(ja, "「確認後に登録可能」は確認済み・登録済みを意味しません。以下の確認は管理者が行います。\n",
                "Review before registration does not mean verified or registered. An administrator must complete the checks below.\n"));
        if (analysis.mode.equals("2faskw")) out.append(t(ja,
            "管理SPのpolicy移行も対象ですが、隔離・移行先照合・ブラウザ試験が必要です。\n",
            "Managed policies are in scope; target isolation, comparison and browser tests are still required.\n"));
        else out.append(t(ja, "元IdPのアクセス制御は自動移行しません。NONE_OBSERVEDも制限なしの保証ではありません。\n",
            "Source authorization is not migrated automatically. NONE_OBSERVED is not a no-restriction guarantee.\n"));
        if (deferred) out.append(t(ja,
            "属性後設定mode：none固定。属性個別の保留は未解決の後工程です。共有policyで属性が送信される場合があるため隔離を維持してください。\n",
            "Deferred attribute mode: fixed none. Attribute holds remain unresolved phase-two work. Shared policies may release attributes; maintain isolation.\n"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Candidate c : analysis.candidates) for (String code : c.diagnostics.stream().filter(ReviewPresentation::blocking)
                .map(Diagnostic::code).distinct().toList()) counts.merge(code, 1, Integer::sum);
        if (!counts.isEmpty()) {
            out.append(t(ja, "\n保留原因（同じSPに複数原因があるため、件数は重複します）：\n",
                "\nHold reasons (counts overlap when an SP has several reasons):\n"));
            counts.forEach((code, count) -> out.append("  ").append(count).append(t(ja, "件：", " SPs: "))
                .append(cause(code, ja)).append(" [").append(SafeFiles.display(code)).append("]\n"));
        }
        out.append(MigrationGuidance.summary(analysis, ja));
        for (Diagnostic d : analysis.diagnostics) out.append(t(ja, "\n全体診断：", "\nGlobal diagnostic: "))
            .append(SafeFiles.display(d.severity())).append(' ').append(SafeFiles.display(d.code())).append('\n').append(d);
        out.append(t(ja, "\n登録前に確認すること（全SP共通・公開前の試験を含む）：\n",
            "\nChecks before registration (common to all SPs; includes pre-cutover tests):\n"));
        List<String> common = commonChecks(ja, deferred);
        for (int i = 0; i < common.size(); i++) out.append("  ").append(i + 1).append(". ").append(common.get(i)).append('\n');
        out.append(t(ja, "共通事項と、下記のSP固有事項の両方を確認してください。d 番号で単体の確認手順・技術詳細を表示します。\n",
            "Complete both the common and SP-specific checks below. Use d NUMBER for a self-contained checklist and technical details.\n"));
        out.append(t(ja, "\n表示対象：", "\nView: ")).append(filter).append('\n');
        int shown = 0;
        for (int i = 0; i < analysis.candidates.size(); i++) {
            Candidate c = analysis.candidates.get(i);
            if (filter.equals("held") && !c.blocked() || filter.equals("ready") && c.blocked()) continue;
            out.append(candidate(c, i + 1, ja)); shown++;
        }
        if (shown == 0) out.append(t(ja, "該当するSPはありません。\n", "No SPs match this view.\n"));
        return out.append(t(ja,
            "\n操作：番号(1,4) / d 番号(詳細) / held(保留のみ) / ready(確認候補のみ) / all(全件) / q(終了)\n",
            "\nCommands: numbers (1,4) / d NUMBER (details) / held / ready / all / q (cancel)\n"))
            .append(t(ja, "番号は絞り込み後も同じです。操作は移行先の対話実行時に利用できます。\n",
                "Numbers remain stable across views. Commands are available during interactive target selection.\n")).toString();
    }

    private static String candidate(Candidate c, int number, boolean ja) {
        StringBuilder out = new StringBuilder("\n[").append(number).append("] ").append(displayName(c)).append("  ")
            .append(c.blocked() ? t(ja, "登録保留", "HELD") : t(ja, "確認後に登録可能", "REVIEW BEFORE REGISTRATION"))
            .append('\n').append(t(ja, "  管理名：", "  Management name: ")).append(SafeFiles.display(c.name)).append('\n')
            .append("  entityID: ").append(SafeFiles.display(c.entity)).append('\n')
            .append(t(ja, "  元ファイル：", "  Source file: ")).append(SafeFiles.display(c.source)).append('\n')
            .append(t(ja, "  アクセス制御：", "  Authorization: ")).append(access(c, ja)).append('\n');
        if (c.attributesDeferred) out.append(t(ja, "  属性設定：未移行（none固定、元条件の保留は未解決）\n",
            "  Attribute configuration: PENDING (fixed none; source holds unresolved)\n"));
        out.append(t(ja, "  作業分類：", "  Work categories: "))
            .append(String.join(" / ", MigrationGuidance.categories(c).stream().map(k -> k.label(ja) + " [" + k + "]").toList())).append('\n');
        if (c.blocked()) {
            List<Diagnostic> blocks = c.diagnostics.stream().filter(ReviewPresentation::blocking)
                .sorted(Comparator.comparingInt(d -> priority(d.code()))).toList();
            Diagnostic primary = blocks.getFirst();
            out.append(t(ja, "  主な原因：", "  Primary reason: ")).append(cause(primary, ja)).append('\n')
                .append(t(ja, "  該当箇所：", "  Location: ")).append(SafeFiles.display(primary.location())).append('\n')
                .append(t(ja, "  次の作業：", "  Next action: ")).append(action(primary, ja)).append('\n')
                .append(t(ja, "  関連コード：", "  Related codes: ")).append(SafeFiles.display(String.join(",", blocks.stream().map(Diagnostic::code).distinct().toList()))).append('\n')
                .append(t(ja, "  他の原因・元の詳細：d ", "  Other reasons and original details: d ")).append(number).append('\n');
            for (Diagnostic d : blocks.stream().distinct().toList())
                out.append(t(ja, "  判定根拠：", "  Decision evidence: ")).append(SafeFiles.display(d.code()))
                    .append(" at ").append(SafeFiles.display(d.location())).append('\n')
                    .append("    ").append(SafeFiles.display(d.detail())).append('\n');
        } else {
            out.append(t(ja, "  SP固有の確認事項（上記共通事項と併せて確認）：\n", "  SP-specific checks (in addition to common checks above):\n"));
            List<String> checks = specificChecks(c, ja);
            for (int i = 0; i < checks.size(); i++) out.append("    ").append(i + 1).append(". ").append(checks.get(i)).append('\n');
        }
        out.append(MigrationGuidance.work(c, ja, false));
        out.append(t(ja, "  詳しい作業・完了確認：migration-actions.txt / d ", "  Detailed work/completion checks: migration-actions.txt / d ")).append(number).append('\n');
        return out.toString();
    }

    static String detail(Candidate c, int number, boolean ja) {
        StringBuilder out = new StringBuilder(candidate(c, number, ja));
        out.append('\n').append(MigrationGuidance.work(c, ja, true));
        out.append(t(ja, "\nこのSPの確認手順（共通事項を含む・確認済みの証拠ではない）：\n",
            "\nSelf-contained checklist for this SP (includes common checks; not proof of verification):\n"));
        List<String> checks = checks(c, ja);
        for (int i = 0; i < checks.size(); i++) out.append("  ").append(i + 1).append(". ").append(checks.get(i)).append('\n');
        if (c.blocked()) {
            out.append(t(ja, "\n保留原因の詳細（すべて解消するまで登録不可）：\n",
                "\nAll hold reasons (each must be resolved before registration):\n"));
            for (Diagnostic d : c.diagnostics.stream().filter(ReviewPresentation::blocking).toList())
                out.append("  ").append(cause(d, ja)).append(" [").append(SafeFiles.display(d.code())).append("]\n")
                    .append(t(ja, "  対処：", "  Action: ")).append(action(d, ja)).append('\n').append(d);
        }
        out.append(t(ja, "\n原本・snapshotの確認先：\n", "\nOriginal and snapshot evidence:\n"))
            .append("  source: ").append(SafeFiles.display(c.source)).append('\n')
            .append("  metadata: ").append(SafeFiles.display(c.metadata)).append('\n')
            .append("  metadata_sha256: ").append(SafeFiles.display(c.hash)).append('\n');
        if (!c.sourceMetadataHash.isEmpty()) out.append("  original_metadata: evidence/metadata-sources/")
            .append(SafeFiles.display(c.sourceMetadataHash)).append(".xml\n  original_metadata_sha256: ")
            .append(SafeFiles.display(c.sourceMetadataHash)).append('\n');
        out.append(t(ja, "hash一致だけでは配信元の真正性・署名検証を証明しません。期限を手作業で延長しないでください。\n",
            "Matching hashes do not prove authenticity or signature validity. Do not manually extend expiry.\n"))
            .append(t(ja, "\n技術詳細・元の診断（条件や設定を省略せず記録）：\n",
                "\nTechnical evidence and original diagnostics:\n")).append(MigrationBundle.detail(c));
        return out.toString();
    }

    private static List<String> commonChecks(boolean ja, boolean deferred) {
        return List.of(
            t(ja, "原本の配信元・entityID・保存原本path/hashを確認。hash一致だけでは真正性やXML署名検証を証明しない。",
                "Confirm original provenance, entityID and saved source path/hash. Hashes alone prove neither authenticity nor XML signature validity."),
            t(ja, "信頼鍵/trust engineによる原本署名の別途検証、親/entityのvalidUntil・cacheDuration、サーバ時刻、filter順序・SP roleを確認。未署名なら元の許容条件と別経路で真正性を確認。抽出XMLは元署名を継承しない。",
                "Independently verify original signatures using trusted keys/trust engines; check parent/entity validity, cacheDuration, server clock, filter order and SP roles. Unsigned originals need explicit source acceptance and independent provenance checks; snapshots do not inherit signatures."),
            deferred ? t(ja, "属性送信は後工程。隔離中に元条件、resolver/mapping、属性承認/profile、filtered AACLIとSP受信属性を確認。noneは共有policyの送信を禁止しない。",
                "Attribute release is phase-two work: review source rules, resolver/mapping, approvals/profiles, filtered AACLI and received attributes while isolated. none does not suppress shared release policies.")
                : t(ja, "表示された属性の送信承認、新IdPのresolver/SAML mapping、対象ユーザーのAACLI結果を確認。requested属性は送信承認ではない。",
                    "Approve the listed release attributes and verify target resolver/SAML mapping and representative-user AACLI results. Requested attributes are not release approval."),
            t(ja, "移行先dry-runの管理名/profile/MFA/ACS許可hostを照合。global MFAはコピーされないため全体方針も比較する。",
                "Compare target dry-run names/profiles/MFA/ACS host allowlists. Global MFA is not copied; compare global policies too."),
            t(ja, "登録前に認可の移行方法を決める。一般モードは自動移行せず、管理モードはpolicy差分とContextCheckを照合。登録後も隔離を維持し、許可ユーザーと拒否ユーザーを試験。未検出も制限なしの保証ではない。",
                "Plan authorization before registration. Generic authorization is not copied; managed policies need target comparison and ContextCheck review. Keep the target isolated after registration and test allowed and denied users. No observed restriction is not a guarantee."),
            t(ja, "公開前にNameID/暗号化条件、SP側の新IdP entityID/署名証明書の信頼設定、ブラウザSSO・属性・MFAを試験。SP登録だけでSP側の信頼は切り替わらない。",
                "Before cutover verify NameID/encryption and SP trust in the new IdP entityID/signing certificates, then test browser SSO, attributes and MFA. Registration does not switch SP trust."));
    }

    private static List<String> specificChecks(Candidate c, boolean ja) {
        var checks = new java.util.ArrayList<String>();
        checks.add(t(ja, "原本：", "Original: ") + SafeFiles.display(c.source)
            + t(ja, "。署名/filter/期限の個別根拠はd 番号を参照。", ". See d NUMBER for signature/filter/validity evidence."));
        checks.add(c.attributesDeferred ? t(ja, "元の送信属性（未移行、未知条件があれば網羅性は未確認）：", "Source attributes (deferred; unresolved conditions may hide others): ") + attributes(c)
            : t(ja, "送信予定属性：", "Planned release attributes: ") + attributes(c));
        if (c.managed != null) checks.add(t(ja, "移行予定：", "Managed intent: ") + "profile=" + SafeFiles.display(c.managed.profile())
            + ", MFA=" + SafeFiles.display(c.managed.mfa()) + ", CIDRs=" + SafeFiles.display(c.managed.cidrs().toString()));
        if (has(c, "METADATA_REFRESH_BEHAVIOR_CHANGED")) checks.add(t(ja,
            "自動更新は引き継がれない。期限前・SP鍵変更時のsnapshot再取得/更新方法と担当者を決める。",
            "Automatic refresh is not retained. Assign a snapshot refresh owner/procedure before expiry or SP key changes."));
        if (c.access != null) for (String evidence : c.access.evidence())
            if (evidence.startsWith("context_check_condition=") || evidence.startsWith("context_check_unresolved=")
                    || evidence.startsWith("relying_party_override="))
                checks.add(t(ja, "利用制限の確認：", "Authorization evidence to review: ") + SafeFiles.display(evidence));
        if (c.accessPending()) checks.add(t(ja, "認可は別途再設定。access-control.txt / d 番号の条件と許可/拒否試験を確認。",
            "Reconfigure authorization separately; review access-control.txt / d NUMBER and allow/deny tests."));
        return List.copyOf(checks);
    }

    static List<String> checks(Candidate c, boolean ja) {
        var checks = new java.util.ArrayList<String>();
        checks.add(t(ja, "原本 " + SafeFiles.display(c.source) + " の配信元・対象entityIDが正しいこと。保存原本がある場合は d 番号で原本pathとSHA-256を照合。hashだけで署名確認の代用にしない。",
            "Confirm the authoritative source and entityID in " + SafeFiles.display(c.source) + ". Use d NUMBER to compare the saved original path/hash; hashes do not replace signature verification."));
        if (c.metadataReviewRequired() || has(c, "METADATA_SIGNATURE_UNVERIFIED")) checks.add(t(ja,
            "原本のXML署名を信頼する署名鍵/trust engineで別途検証し、親とentityのvalidUntil・cacheDuration、filter順序・SP roleを確認。署名がない場合は未署名を許容する元の条件と別経路での真正性確認が必要。抽出XMLは元署名を継承しない。詳細のfilter設定/保存原本を参照。",
            "Independently verify original XML signatures with trusted keys/trust engines and check parent/entity validity, cacheDuration, filter order and SP roles. Unsigned originals require explicit source acceptance and independent authenticity verification. Extracted XML does not inherit signatures."));
        else checks.add(t(ja, "メタデータのvalidUntilとSP証明書・ACSが現行で正しいこと。署名がある場合は信頼鍵/trust engineで別途検証し、未署名なら元の許容条件と配信元の真正性を別経路で確認（詳細に解析値を表示）。",
            "Check current validity, SP certificates and ACS endpoints. Verify signatures with trusted keys/trust engines; unsigned originals require explicit source acceptance and independent provenance checks. See detailed evidence."));
        if (has(c, "METADATA_REFRESH_BEHAVIOR_CHANGED")) checks.add(t(ja,
            "動的/URLメタデータの自動更新は引き継がれないため、期限前・SP鍵変更時のsnapshot再取得/更新方法と担当者を決める。",
            "Automatic dynamic/URL refresh is not retained. Assign an owner and a snapshot update procedure before expiry or SP key changes."));
        checks.add(c.attributesDeferred ? t(ja, "元の送信属性（未移行）：" + attributes(c) + "。none固定で登録し、元条件・resolver/mapping・承認/profile・filtered AACLI/SP受信属性は後工程で確認。未知条件があればこの属性集合がすべてとは限らない。",
            "Source attributes (deferred): " + attributes(c) + ". Register with none; review original rules, resolver/mapping, approval/profile and filtered AACLI/received attributes in phase two. Unknown conditions may hide other attributes.")
            : t(ja, "送信予定属性：" + attributes(c) + "。各属性の送信承認と移行先resolver/SAML mappingを確認し、対象ユーザーのAACLI結果を照合。requested属性は送信承認ではない。",
                "Planned release attributes: " + attributes(c) + ". Confirm release approval and target resolver/SAML mapping, then compare AACLI results for a representative user. Requested attributes are not approval."));
        if (c.managed != null) checks.add(t(ja,
            "管理profile=" + c.managed.profile() + "、MFA=" + c.managed.mfa() + "、CIDRs=" + c.managed.cidrs() + " を移行先計画と照合。global MFAはコピーされないため元/新の全体方針も比較。",
            "Compare profile=" + c.managed.profile() + ", MFA=" + c.managed.mfa() + ", CIDRs=" + c.managed.cidrs() + " with the target plan. Global MFA is not copied; compare source and target global policies."));
        else checks.add(t(ja, "移行先dry-runで管理名・属性profile・MFA方針とACS許可hostを確認。元IdPのMFA方針が自動的に再現されるとは扱わない。",
            "Review the target dry-run name, attribute profile, MFA policy and ACS host allowlist. Source MFA behavior is not automatically reproduced."));
        if (c.accessPending()) checks.add(t(ja,
            "登録前に利用制限を access-control.txt / d 番号で確認し、再設定方法を決める。登録後も隔離を維持し、新IdPで等価な条件を設定して許可ユーザーと拒否ユーザーを試験。両方期待どおりになるまで公開しない。",
            "Before registration, review access-control.txt / d NUMBER and plan equivalent authorization. After registration keep the target isolated, configure the conditions, and test allowed and denied users before exposure."));
        else if (c.managed != null) checks.add(t(ja,
            "隔離した新IdPで移行先policy差分、ContextCheck有効化、必要なallow/deny判定を確認する。設定照合だけで実ログイン成功を保証しない。",
            "On the isolated target review policy differences, ContextCheck activation and required allow/deny tests. Configuration comparison does not certify login."));
        else checks.add(t(ja, "アクセス制御は静的解析で未検出でも制限なしとは保証されない。元/新IdPの条件を確認し、必要な許可・拒否試験を行う。",
            "No observed authorization is not a no-restriction guarantee. Compare source/target conditions and run required allow/deny tests."));
        checks.add(t(ja, "公開前にNameID形式・暗号化条件とSP側の新IdP entityID/署名証明書の信頼設定を確認し、ブラウザSSO・送信属性・MFAを試験。SP登録だけでSP側の信頼は切り替わらない。",
            "Before cutover, verify NameID/encryption requirements and SP trust in the new IdP entityID/signing certificates. Test browser SSO, released attributes and MFA; registration does not switch SP trust."));
        return List.copyOf(checks);
    }

    private static String attributes(Candidate c) {
        if (c.attributes.isEmpty()) return "none";
        return SafeFiles.display(String.join(",", new TreeSet<>(c.attributes)));
    }
    private static String displayName(Candidate c) {
        try {
            String host = java.net.URI.create(c.entity).getHost();
            if (host != null) return SafeFiles.display(host);
        } catch (IllegalArgumentException ex) { /* Non-URL entityIDs remain visible by management name. */ }
        return SafeFiles.display(c.name);
    }
    private static String access(Candidate c, boolean ja) {
        if (c.accessPending()) return t(ja, "別途確認・再設定が必要", "Separate review/reconfiguration required") + " [" + c.accessState() + "]";
        if (c.managed != null) return t(ja, "管理policy移行・移行先照合が必要", "Managed policy migration/target comparison required");
        if (c.access == null) return t(ja, "未評価（別途確認が必要）", "Not assessed; separate review required");
        return t(ja, "静的解析で未検出（制限なしの保証ではない）", "Not observed statically (not a no-restriction guarantee)") + " [" + c.accessState() + "]";
    }
    private static boolean has(Candidate c, String code) { return c.diagnostics.stream().anyMatch(d -> d.code().equals(code)); }
    private static boolean blocking(Diagnostic d) { return d.severity().equals("BLOCK") || d.severity().equals("ERROR"); }
    private static int priority(String code) { return code.equals("METADATA_EXTRACTION_UNSUPPORTED") ? 2 : code.equals("METADATA_SOURCE_FILTER_REJECTED") ? 1 : 0; }
    private static String cause(String code, boolean ja) {
        Guidance g = guidance(code);
        return g == null ? t(ja, "未対応または不整合の設定（詳細を確認）", "Unsupported or inconsistent input; see details") : ja ? g.causeJa() : g.causeEn();
    }
    private static String cause(Diagnostic d, boolean ja) {
        if (d.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED")) return switch (d.reason()) {
            case "ATTRIBUTE_ID_WHITESPACE" -> t(ja, "属性IDの前後に空白があります。元resolverの正式IDと照合が必要です。", "The attribute ID has surrounding whitespace; compare its exact source resolver ID.");
            case "ATTRIBUTE_ID_MISSING" -> t(ja, "属性IDが空欄または空白だけです。", "The attribute ID is missing or whitespace-only.");
            case "ATTRIBUTE_ID_INVALID" -> t(ja, "属性IDがCLIの対応形式ではありません。意図・正式IDを確認してください。", "The attribute ID is outside the CLI-supported form; verify its intent and exact ID.");
            case "ATTRIBUTE_VALUE_RULE_MANUAL" -> t(ja, "値限定送信を全値送信profileへ自動変換できません。手動で同等設定を移行します。", "Value-limited release cannot be represented by an all-value profile; manually migrate equivalent filtering.");
            case "ATTRIBUTE_DENY_RULE_MANUAL" -> t(ja, "deny条件を保持した手動移行が必要です。設定ミスとは限りません。", "Manual migration must preserve deny filtering; this does not necessarily indicate a configuration error.");
            default -> cause(d.code(), ja);
        };
        if (!VALIDITY_CODES.contains(d.code())) return cause(d.code(), ja);
        for (String reason : List.of("VALID_UNTIL_MISSING", "VALID_UNTIL_MALFORMED", "VALID_UNTIL_EXPIRED",
                "CACHE_DURATION_NONPOSITIVE", "CACHE_DURATION_UNSUPPORTED", "VALIDITY_WINDOW_EXCEEDED",
                "MAX_VALIDITY_INTERVAL_NEGATIVE", "MAX_VALIDITY_INTERVAL_UNSUPPORTED", "ROOT_SIGNATURE_MISSING")) {
            if (!d.reason().equals(reason)) continue;
            return switch (reason) {
                case "VALID_UNTIL_MISSING" -> t(ja, "元filterが要求するvalidUntilがありません。", "The validUntil required by the source filter is missing.");
                case "VALID_UNTIL_MALFORMED" -> t(ja, "validUntilを日時として解析できません。", "The validUntil timestamp cannot be parsed.");
                case "VALID_UNTIL_EXPIRED" -> t(ja, "validUntilが検証日時以前です。entity/親groupの判定根拠を確認してください。", "The validUntil is at or before the analysis time; inspect the entity/ancestor evidence.");
                case "CACHE_DURATION_NONPOSITIVE" -> t(ja, "cacheDurationが0以下です。", "The cacheDuration is zero or negative.");
                case "CACHE_DURATION_UNSUPPORTED" -> t(ja, "cacheDurationを固定期間として安全に扱えません。", "The cacheDuration cannot be safely represented as a fixed duration.");
                case "VALIDITY_WINDOW_EXCEEDED" -> t(ja, "validUntilが元filterの許容上限を超えています。", "The validUntil exceeds the source filter's allowed upper bound.");
                case "MAX_VALIDITY_INTERVAL_NEGATIVE" -> t(ja, "元filterのmaxValidityIntervalが負の値です。", "The source filter's maxValidityInterval is negative.");
                case "MAX_VALIDITY_INTERVAL_UNSUPPORTED" -> t(ja, "元filterのmaxValidityIntervalを安全に解釈できません。", "The source filter's maxValidityInterval cannot be safely interpreted.");
                default -> t(ja, "元filterが要求するrootのXML署名がありません。", "The root XML signature required by the source filter is missing.");
            };
        }
        return cause(d.code(), ja);
    }
    private static String action(Diagnostic d, boolean ja) {
        if (d.code().equals("ATTRIBUTE_CONDITION_UNSUPPORTED") && !d.reason().isEmpty()) {
            return switch (MigrationGuidance.category(d)) {
                case SOURCE_CONFIG_REVIEW -> t(ja, "該当元XMLとresolver/registryの正式IDを比較し、意図確認・バックアップ後に修正して再解析。自動trimはしない。",
                    "Compare exact source XML/resolver/registry IDs, confirm intent, back up, correct and re-export; no automatic trimming.");
                case MANUAL_ATTRIBUTE_RELEASE -> t(ja, "対象SPのValue/Regex/denyを新IdPのfilterへ同等移行し、filtered AACLIと受信属性で検証。本ツールでは保留を維持し、ANYへ置換しない。",
                    "Manually reproduce this SP's Value/Regex/deny filtering; verify filtered AACLI and received attributes. This tool keeps the hold; do not substitute ANY.");
                default -> t(ja, "原XMLのref/script/独自条件/tag等の意味を元IdP担当者と確認。未対応を設定ミスと断定しない。",
                    "Confirm source ref/script/custom/tag semantics with its administrator; unsupported does not imply erroneous configuration.");
            };
        }
        Guidance g = guidance(d.code());
        return g == null ? SafeFiles.display(d.remedy()) : ja ? g.actionJa() : g.actionEn();
    }
    private static Guidance guidance(String code) {
        return switch (code) {
            case "METADATA_EFFECTIVE_EXPIRED" -> new Guidance("メタデータの期限・cacheDurationに問題があります（期限切れとは限りません）。", "原本と親groupのvalidUntil/cacheDuration、およびサーバ時刻を確認。期限切れなら信頼する配信元から最新版を取得して再解析。",
                "Metadata validity/cacheDuration is invalid or unsupported; not necessarily expired.", "Check original/parent validity, cacheDuration and server time. If expired, obtain current authoritative metadata and re-analyze.");
            case "METADATA_SOURCE_FILTER_REJECTED" -> new Guidance("元IdPの署名・有効期限filter条件を満たしていません。", "詳細のcauseで署名欠落か期限条件かを確認。原本と元filter設定を照合し、検証を無効化せず最新版で再解析。",
                "Source signature/validity filter requirements are not met.", "Read the original cause to distinguish missing signature from validity conditions. Compare source filters and authoritative metadata; do not bypass validation.");
            case "METADATA_EXTRACTION_UNSUPPORTED", "METADATA_PARENT_CONTEXT_REQUIRED" -> new Guidance("独立したSPメタデータを安全に抽出できません。", "他の保留原因を先に確認。親group/inline構成を詳細で確認し、対応可能な原本または正式な単体メタデータで再解析。",
                "An independent SP snapshot cannot be safely extracted.", "Review other hold causes first, then inspect parent/inline structure and re-analyze supported originals or authoritative standalone metadata.");
            case "ATTRIBUTE_CONDITION_UNSUPPORTED", "ATTRIBUTE_POLICY_UNRESOLVED" -> new Guidance("属性送信条件を安全に自動変換できません。", "該当attribute-filter.xmlのdeny/value/ref/tag条件と対象属性を確認。移行先で等価な送信制御を設計し、未知の条件をANYへ置換しない。",
                "Attribute release conditions cannot be safely reduced.", "Review deny/value/ref/tag conditions and attributes at the reported filter location. Design equivalent release control; never replace unknown conditions with ANY.");
            case "METADATA_FILTER_UNSUPPORTED" -> new Guidance("メタデータfilterの設定・加工が未対応です。", "詳細のfilter種類・設定箇所を確認し、加工後の意味を検証。元filterを削除して回避しない。",
                "Metadata filter configuration/transformation is unsupported.", "Inspect the filter type/location and resulting semantics. Do not remove source filters to bypass the hold.");
            case "CERTIFICATE_INVALID" -> new Guidance("SPのX.509証明書を解析できません。", "配信元の正式なSP証明書・メタデータを確認して再取得。署名検証を無効化しない。",
                "The SP X.509 certificate cannot be parsed.", "Obtain authoritative SP certificate/metadata; do not disable signature validation.");
            case "MANAGED_SOURCE_INCONSISTENT", "SOURCE_MFA_DRIFT", "SOURCE_PROFILE_REVISION_MISMATCH", "SOURCE_ACCESS_REVISION_MISMATCH" -> new Guidance("管理registryと実設定の整合性に問題があります。", "詳細の相違項目を既存の管理CLI・通常の設定手順で確認し、元設定を整合させてから再解析。",
                "Managed registry and effective configuration are inconsistent.", "Review the specific differences using existing management procedures and re-export a consistent source.");
            default -> null;
        };
    }
    private static String t(boolean ja, String japanese, String english) { return ja ? japanese : english; }
    private ReviewPresentation() { }
}
