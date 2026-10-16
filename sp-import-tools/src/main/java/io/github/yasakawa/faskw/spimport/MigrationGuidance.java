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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Informational work instructions, never registration or authorization decisions. */
final class MigrationGuidance {
    enum Category {
        SOURCE_CONFIG_REVIEW("元ファイル・設定要確認", "Source file/configuration review"),
        SOURCE_METADATA_REFRESH("元メタデータ更新", "Refresh authoritative source metadata"),
        SOURCE_METADATA_REVIEW("元メタデータ・filter要確認", "Source metadata/filter review"),
        MANUAL_ATTRIBUTE_RELEASE("属性送信条件の手動移行", "Manual attribute release migration"),
        ADDITIONAL_REVIEW("追加調査・個別判断", "Additional investigation/individual review"),
        EXTRACTION_REVIEW("抽出・配布方式要確認", "Extraction/distribution review"),
        MANAGED_CONSISTENCY("管理設定の整合性確認", "Managed configuration consistency"),
        MANUAL_AUTHORIZATION("アクセス制御の別途移行・確認", "Separate authorization migration/review"),
        TARGET_QUALIFICATION("移行先照合・公開前試験", "Target comparison/pre-cutover qualification");

        private final String ja;
        private final String en;
        Category(String ja, String en) { this.ja = ja; this.en = en; }
        String label(boolean japanese) { return japanese ? ja : en; }
    }

    static Category category(Diagnostic d) {
        return switch (d.code()) {
            case "ATTRIBUTE_CONDITION_UNSUPPORTED" -> switch (d.reason()) {
                case "ATTRIBUTE_ID_WHITESPACE", "ATTRIBUTE_ID_INVALID", "ATTRIBUTE_ID_MISSING" -> Category.SOURCE_CONFIG_REVIEW;
                case "ATTRIBUTE_VALUE_RULE_MANUAL", "ATTRIBUTE_DENY_RULE_MANUAL" -> Category.MANUAL_ATTRIBUTE_RELEASE;
                default -> Category.ADDITIONAL_REVIEW;
            };
            case "METADATA_EFFECTIVE_EXPIRED", "METADATA_SOURCE_FILTER_REJECTED" -> switch (d.reason()) {
                case "VALID_UNTIL_EXPIRED" -> Category.SOURCE_METADATA_REFRESH;
                case "VALID_UNTIL_MISSING", "VALID_UNTIL_MALFORMED", "VALIDITY_WINDOW_EXCEEDED", "ROOT_SIGNATURE_MISSING" -> Category.SOURCE_METADATA_REVIEW;
                case "CACHE_DURATION_NONPOSITIVE", "MAX_VALIDITY_INTERVAL_NEGATIVE" -> Category.SOURCE_CONFIG_REVIEW;
                default -> Category.ADDITIONAL_REVIEW;
            };
            case "METADATA_EXTRACTION_UNSUPPORTED", "METADATA_PARENT_CONTEXT_REQUIRED" -> Category.EXTRACTION_REVIEW;
            case "MANAGED_SOURCE_INCONSISTENT", "SOURCE_MFA_DRIFT", "SOURCE_PROFILE_REVISION_MISMATCH", "SOURCE_ACCESS_REVISION_MISMATCH" -> Category.MANAGED_CONSISTENCY;
            case "SOURCE_REFERENCE_MISSING", "METADATA_FILE_NOT_FOUND", "XML_PARSE_ERROR", "CONFIG_FILE_NOT_FOUND" -> Category.SOURCE_CONFIG_REVIEW;
            case "CERTIFICATE_INVALID" -> Category.SOURCE_METADATA_REVIEW;
            default -> Category.ADDITIONAL_REVIEW;
        };
    }

    static Set<Category> categories(Candidate c) {
        EnumSet<Category> result = EnumSet.noneOf(Category.class);
        for (Diagnostic d : c.diagnostics) if (blocking(d)) result.add(category(d));
        if (c.accessPending()) result.add(Category.MANUAL_AUTHORIZATION);
        if (Set.of("UNRESOLVED", "LEGACY_UNASSESSED", "NOT_ASSESSED").contains(c.accessState())) result.add(Category.ADDITIONAL_REVIEW);
        result.add(Category.TARGET_QUALIFICATION);
        return result;
    }

    static String summary(Analysis analysis, boolean ja) {
        StringBuilder out = new StringBuilder(t(ja, "\n作業分類（SP件数・分類間は重複。分類は確認完了や登録可否を意味しません）：\n",
            "\nWork categories (SP counts overlap; classification is not verification or eligibility):\n"));
        for (Category category : Category.values()) {
            long count = analysis.candidates.stream().filter(c -> categories(c).contains(category)).count();
            if (count > 0) out.append("  ").append(count).append(t(ja, "件：", " SPs: ")).append(category.label(ja))
                .append(" [").append(category).append("]\n");
        }
        return out.toString();
    }

    static String work(Candidate c, boolean ja, boolean evidence) {
        StringBuilder out = new StringBuilder(t(ja, "  このSPの移行作業（順番に確認・自動実行しない）：\n",
            "  Migration work for this SP (ordered review; not executed automatically):\n"));
        int step = 0;
        for (Category category : categories(c)) {
            out.append("    ").append(++step).append(". ").append(category.label(ja)).append(" [").append(category).append("]\n");
            List<Diagnostic> issues = c.diagnostics.stream().filter(MigrationGuidance::blocking)
                .filter(d -> category(d) == category).distinct().toList();
            for (Diagnostic d : issues) {
                out.append(t(ja, "       対象：", "       Input: ")).append(SafeFiles.display(d.location()))
                    .append(" [").append(SafeFiles.display(d.code())).append(']');
                if (!d.reason().isEmpty()) out.append(" reason=").append(d.reason());
                out.append('\n');
                if (evidence) out.append("       ").append(SafeFiles.display(d.detail())).append('\n');
            }
            if (category == Category.MANUAL_AUTHORIZATION && evidence && c.access != null)
                for (String item : c.access.evidence()) out.append("       ").append(SafeFiles.display(item)).append('\n');
            if (evidence) for (String instruction : instructions(category, c, ja)) out.append("       ").append(instruction).append('\n');
            else out.append("       ").append(shortInstruction(category, ja)).append('\n');
        }
        if (c.blocked()) out.append(t(ja,
            "    登録は保留のままです。前工程を直しても他の保留が残る場合があります。新しい保存先へ再解析し、全保留を再確認してください。TXT編集では解除できません。\n",
            "    Registration remains HELD. Resolving an earlier step may leave other holds. Re-export to a new directory and reassess every hold; editing TXT cannot unblock registration.\n"));
        return out.toString();
    }

    private static String shortInstruction(Category category, boolean ja) {
        return switch (category) {
            case SOURCE_CONFIG_REVIEW -> t(ja, "該当原文を元resolver/設定と照合し、意図確認・バックアップ後に修正して再解析。自動trim/改名はしない。",
                "Compare exact source operands with the resolver/configuration; confirm intent, back up, correct and re-export. No automatic trim/rename.");
            case SOURCE_METADATA_REFRESH -> t(ja, "原本/親groupの期限と時計、providerの更新失敗を確認。信頼する最新版を取得・署名/期限を検証して再解析。",
                "Check source/parent expiry, clock and provider fetch failures. Obtain and verify current authoritative metadata, then re-export.");
            case SOURCE_METADATA_REVIEW -> t(ja, "実値と元filter/trust設定を照合し、欠落/不正値/上限超過等を確認。検証を無効化して回避しない。",
                "Compare actual values with source filters/trust; investigate missing/malformed/excessive inputs without disabling validation.");
            case MANUAL_ATTRIBUTE_RELEASE -> t(ja, "このSPの値限定/denyを新IdPで同等設定し、filtered AACLIで一致/不一致/空値/複数値を確認。全値送信へ変換せず、本ツールでは保留を維持。",
                "Reconstruct value/deny filtering for this SP; test filtered AACLI with matching/nonmatching/absent/mixed values. Never broaden to all-value release; this tool keeps the hold.");
            case ADDITIONAL_REVIEW -> t(ja, "原XMLと実行時の意味を元IdP担当者と確認。未対応を設定ミスと断定せず、式を実行・推測・ANY置換しない。",
                "Confirm source XML/runtime semantics with its administrator. Unsupported is not erroneous; do not execute, guess or replace with ANY.");
            case EXTRACTION_REVIEW -> t(ja, "先に他のmetadata保留を解消して再解析。残る場合は正式単体metadataか別管理providerで個別移行を検討。",
                "Resolve other metadata holds first and re-export. If still held, consider authoritative standalone metadata or separately managed providers.");
            case MANAGED_CONSISTENCY -> t(ja, "元registry/profile/MFA/access差分を既存CLIで確認し、承認した管理意図へ整合させて再解析。",
                "Review registry/profile/MFA/access differences with the existing CLI; restore approved source intent and re-export.");
            case MANUAL_AUTHORIZATION -> t(ja, "このSPのaccess-control.txtの条件を再設定し、隔離した新IdPで許可/拒否ユーザーを試験。登録だけでは移行しない。",
                "Reconstruct this SP's access-control.txt conditions and test allowed/denied users on an isolated target; registration is not migration.");
            case TARGET_QUALIFICATION -> t(ja, "前工程の後に移行先dry-runの属性/MFA/ACSとSP信頼/SSOを照合・試験。公開承認まで隔離を維持。",
                "After earlier work, qualify target dry-run attributes/MFA/ACS, SP trust and SSO; keep isolation until cutover approval.");
        };
    }

    private static List<String> instructions(Category category, Candidate c, boolean ja) {
        String entity = SafeFiles.display(c.entity);
        return switch (category) {
            case SOURCE_CONFIG_REVIEW -> List.of(t(ja,
                "該当元ファイルをバックアップし、原文のID・空白・期間値・参照先・読取権限を確認。属性IDは元resolver/registryの正式IDと照合し、意図を確認してから修正する。ツールはtrim・改名・設定変更を行わない。",
                "Back up the indicated source file. Inspect exact IDs/whitespace/durations/references/read permissions; compare attribute IDs with the source resolver/registry and confirm intent before correcting. No trimming, renaming or configuration edit is performed."));
            case SOURCE_METADATA_REFRESH -> List.of(t(ja,
                "対象SP " + entity + " を含む原本/親groupの期限とサーバ時刻を確認。元metadata providerの更新設定・取得失敗を確認し、信頼する配信元から現行metadataを取得して署名・期限を別途検証。共通の原本なら他SPも同じ更新に依存する。期限を手作業で延長しない。",
                "For " + entity + ", check original/parent expiry and server time. Review the source provider's refresh settings/fetch failures; obtain current authoritative metadata and independently verify signatures/validity. Other SPs may share this input. Never manually extend expiry."));
            case SOURCE_METADATA_REVIEW -> List.of(t(ja,
                "判定根拠の原本値と元filter/trust設定を照合。欠落・不正日時・許容上限超過・署名欠落・証明書解析失敗を区別し、正式metadataまたは承認された設定で再確認する。filter/署名検証を無効化して通過させない。",
                "Compare original values with source filter/trust settings. Distinguish missing/malformed validity, excessive windows, missing signatures and unparseable certificates; reassess authoritative metadata/approved configuration without disabling validation."));
            case MANUAL_ATTRIBUTE_RELEASE -> List.of(
                t(ja, "上記のpolicy・属性ID・Value/Regex/deny条件を原XMLで確認する。全値送信profileでは表現できない条件であり、元設定の誤りとは断定しない。",
                    "Inspect the policy, attribute ID and Value/Regex/deny conditions in the source XML. These cannot be represented by an all-value release profile; this does not imply a source configuration error."),
                t(ja, "新IdPのconf/attribute-filter.xml等で対象entityID " + entity + " に対する同等条件を設計し、既存の共通/管理policyとの組合せも確認。ANYやpermitAny=trueへ置換しない。",
                    "Design equivalent release filtering for entityID " + entity + " in the target conf/attribute-filter.xml or its configured resources; review interactions with shared/managed policies. Do not replace conditions with ANY/permitAny=true."),
                t(ja, "移行先でfiltered AACLIとSP受信属性を確認。一致値・不一致値・空値・複数値の混在を試し、必要な値だけ送信されることを確認する。属性送信条件とログインの許可/拒否条件は別に試験する。",
                    "Compare target filtered AACLI and attributes received by the SP. Test matching, nonmatching, absent and mixed multivalued inputs; verify only intended values are released. Attribute filtering and login authorization are separate tests."),
                t(ja, "本ツールでは保留を維持する。条件を保持した個別移行が必要なら、信頼済み単体metadata・適切なprofile・手動filterを用いる既存CLIの別手順を管理者が計画する。元条件を削除して再解析を通過させない。",
                    "This tool keeps the hold. If individual migration is needed, plan a separate existing-CLI procedure using trusted standalone metadata, an appropriate profile and manual filtering. Do not remove source conditions merely to pass analysis."));
            case ADDITIONAL_REVIEW -> List.of(t(ja,
                "元診断と原XMLを確認。独自namespace、ref、script、式、metadata tag、未評価の認可条件等の実際の意味を元IdP担当者と確認する。未対応を設定ミスと断定せず、実行・推測・ANY置換は行わない。旧bundleで理由が不明なら元IdPを再解析する。",
                "Review original diagnostics/XML and confirm custom namespaces, refs, scripts, expressions, metadata tags or unassessed authorization with the source administrator. Unsupported does not mean erroneous; do not execute, guess or substitute ANY. Re-export legacy bundles if the reason is missing."));
            case EXTRACTION_REVIEW -> List.of(t(ja,
                "先に元metadataの期限/filter等の保留を解消し、親contextを保持できるか再解析する。更新後も抽出保留なら正式な単体metadataを用意するか、動的/連合providerを別管理する方式を検討する。全SPを静的snapshotへ移す必要があるとは限らない。",
                "Resolve earlier metadata validity/filter holds and re-analyze parent context. If extraction still cannot proceed, obtain authoritative standalone metadata or consider separately managed dynamic/federation providers. Not every SP must become a static snapshot."));
            case MANAGED_CONSISTENCY -> List.of(t(ja,
                "元registryと実際のprofile/MFA/access設定の差分を既存管理CLIで確認し、承認した管理意図へ整合させて再解析。履歴/revisionやglobal MFAをそのままコピーして一致させない。",
                "Compare source registry with actual profile/MFA/access configuration using existing management procedures; restore approved intent and re-export. Do not copy history/revisions or global MFA to force equality."));
            case MANUAL_AUTHORIZATION -> List.of(t(ja,
                "対象SP " + entity + " のaccess-control.txt / d 番号で属性・IP・regex・intercept/override等を確認。登録前に再設定方法を決め、隔離した新IdPで等価な条件と許可/拒否ユーザーを試験する。登録だけでは認可は移行しない。",
                "Review access-control.txt / d NUMBER for " + entity + " and its attribute/IP/regex/intercept/override conditions. Plan reconstruction before registration; test equivalent rules and allowed/denied users on an isolated target. Registration alone does not migrate authorization."));
            case TARGET_QUALIFICATION -> List.of(t(ja,
                "前工程の完了後、管理名 " + SafeFiles.display(c.name) + "・送信予定属性・resolver/SAML mapping・ACS許可host・MFAを移行先dry-runで照合。管理モードでは元/新policyも比較。SP側の新IdP信頼、NameID/暗号化、browser SSO・受信属性・MFAを試験し、承認まで隔離を維持する。",
                "After earlier work, compare name " + SafeFiles.display(c.name) + ", planned attributes, resolver/SAML mapping, ACS allowlists and MFA in target dry-run; also compare managed policies. Test SP trust in the new IdP, NameID/encryption, browser SSO, received attributes and MFA; keep isolation until approved."));
        };
    }

    static String report(Analysis analysis, boolean ja) {
        StringBuilder out = new StringBuilder(t(ja, "SP別移行作業 / 説明資料（承認・実行計画ではない）\n",
            "Per-SP migration work / informational only (not approval or an executable plan)\n"));
        out.append(t(ja, "分類やTXT編集は保留を解除しません。元IdP・移行先の設定変更は行いません。\n",
            "Categories/TXT edits cannot unblock registration. No source or target configuration is changed.\n"));
        out.append(summary(analysis, ja));
        for (int i = 0; i < analysis.candidates.size(); i++) {
            Candidate c = analysis.candidates.get(i);
            out.append("\n[").append(i + 1).append("] ").append(SafeFiles.display(c.name)).append(" entityID=")
                .append(SafeFiles.display(c.entity)).append(" registration=").append(c.registrationState()).append('\n');
            out.append(work(c, ja, true));
        }
        return out.toString();
    }

    private static boolean blocking(Diagnostic d) { return Set.of("BLOCK", "ERROR").contains(d.severity()); }
    private static String t(boolean ja, String japanese, String english) { return ja ? japanese : english; }
    private MigrationGuidance() { }
}
