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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ImportOptions {
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private static final Set<String> FLAGS = Set.of("apply", "analysis-only", "non-interactive", "help", "version", "confirm-isolated-target", "registration-only", "extract-aggregate", "confirm-metadata-review", "defer-attributes");
    private static final Set<String> REPEATED = Set.of("metadata-config", "metadata-file", "property-source", "path-map", "entity-id");
    private static final Set<String> SINGLE = Set.of("tool-home", "source-idp-home", "target-idp-home", "bundle-dir", "export-dir", "resume", "lang", "source-type", "context-check-config");

    static ImportOptions parse(String... arguments) throws ImportModel.ImportException {
        ImportOptions options = new ImportOptions();
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            if ("-h".equals(argument)) argument = "--help";
            if (!argument.startsWith("--")) throw new ImportModel.ImportException(2, "unknown argument: " + SafeFiles.display(argument));
            String key = argument.substring(2);
            if (!FLAGS.contains(key) && !SINGLE.contains(key) && !REPEATED.contains(key))
                throw new ImportModel.ImportException(2, "unknown option: " + SafeFiles.display(argument));
            if (!REPEATED.contains(key) && options.values.containsKey(key))
                throw new ImportModel.ImportException(2, "duplicate option: " + argument);
            String value = "true";
            if (!FLAGS.contains(key)) {
                if (++i >= arguments.length || arguments[i].startsWith("--") || arguments[i].isBlank())
                    throw new ImportModel.ImportException(2, "missing value: " + argument);
                value = arguments[i];
                if (value.chars().anyMatch(c -> c < 32 || c == 127)) throw new ImportModel.ImportException(2, "control character in argument");
            }
            options.values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return options;
    }

    void validate() throws ImportModel.ImportException {
        if (flag("help") || flag("version")) return;
        if (has("source-idp-home") == has("bundle-dir")) fail("specify exactly one of --source-idp-home and --bundle-dir");
        if (has("bundle-dir") && REPEATED.stream().filter(k -> !k.equals("entity-id")).anyMatch(this::has)) fail("source options cannot be used with --bundle-dir");
        if (has("bundle-dir") && has("context-check-config")) fail("--context-check-config is a source-only option");
        if (flag("extract-aggregate") && !has("source-idp-home")) fail("--extract-aggregate is a source-only option");
        if (flag("extract-aggregate") && get("source-type", "auto").equals("2faskw")) fail("--extract-aggregate is only available for generic Shibboleth analysis");
        if (flag("confirm-metadata-review") && !has("target-idp-home")) fail("--confirm-metadata-review requires --target-idp-home");
        if (flag("registration-only") && !has("target-idp-home")) fail("--registration-only requires --target-idp-home");
        if (flag("defer-attributes") && !has("target-idp-home")) fail("--defer-attributes requires --target-idp-home");
        if (flag("defer-attributes") && flag("apply") && (!flag("registration-only") || !flag("confirm-isolated-target")))
            fail("--defer-attributes --apply requires --registration-only --confirm-isolated-target; enforce isolation separately");
        if (flag("apply") && (!has("target-idp-home") || flag("analysis-only") || flag("non-interactive")))
            fail("--apply requires --target-idp-home and an interactive terminal; it cannot be combined with analysis-only/non-interactive");
        if (flag("analysis-only") && has("target-idp-home")) fail("--analysis-only cannot be combined with --target-idp-home");
        if (has("resume") && (!has("bundle-dir") || !has("target-idp-home") || !get("resume", "").matches("[A-Za-z0-9-]{1,80}")))
            fail("--resume requires a valid run ID, --bundle-dir and --target-idp-home");
        if (!Set.of("ja", "en").contains(get("lang", "ja"))) fail("--lang must be ja or en");
        if (!Set.of("auto", "shibboleth", "2faskw").contains(get("source-type", "auto"))) fail("--source-type must be auto, shibboleth or 2faskw");
        if (has("bundle-dir") && has("source-type")) fail("bundle source mode cannot be overridden");
    }

    private void fail(String message) throws ImportModel.ImportException { throw new ImportModel.ImportException(2, message); }
    boolean has(String key) { return values.containsKey(key); }
    boolean flag(String key) { return has(key); }
    String get(String key, String fallback) { return has(key) ? values.get(key).getFirst() : fallback; }
    List<String> all(String key) { return values.getOrDefault(key, List.of()); }
    Path path(String key) { return Path.of(get(key, "")).toAbsolutePath().normalize(); }
    Path output() { return has("export-dir") ? path("export-dir") : Path.of(get("tool-home", ".")).toAbsolutePath().normalize().resolve("sp-migration"); }
}
