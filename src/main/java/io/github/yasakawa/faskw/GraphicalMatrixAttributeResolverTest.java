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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GraphicalMatrixAttributeResolverTest {
    private static final int MAX_OUTPUT_BYTES = 4_194_304;

    private GraphicalMatrixAttributeResolverTest() {
    }

    static Map<String, List<String>> resolve(final GraphicalMatrixSpManagementConfig config,
            final String principal, final String requester) throws Exception {
        if (principal == null || !principal.matches("[A-Za-z0-9._@-]{1,256}")) {
            throw new IllegalArgumentException("invalid principal for attribute resolver test");
        }
        final Path executable = config.idpHome().resolve("bin/aacli.sh");
        if (!Files.isExecutable(executable)) {
            throw new IOException("AACLI is missing or not executable: " + executable);
        }
        final ProcessBuilder builder = new ProcessBuilder(executable.toString(),
            "--principal", principal, "--requester", requester, "--unfiltered")
            .redirectErrorStream(true);
        builder.environment().put("IDP_BASE_URL", config.reloadBaseUrl());
        final Process process = builder.start();
        final byte[] bytes = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            process.destroyForcibly();
            throw new IOException("AACLI output exceeds 4 MiB");
        }
        final int status = process.waitFor();
        final String output = new String(bytes, StandardCharsets.UTF_8).trim();
        if (status != 0) {
            throw new IOException("AACLI failed (" + status + "): " + safeMessage(output));
        }
        return parseOutput(output);
    }

    static Map<String, List<String>> parseOutput(final String output) throws IOException {
        final Object parsed = GraphicalMatrixJson.parse(output);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IOException("AACLI output root is not JSON object");
        }
        final Object attributes = findAttributes(root);
        final Map<String, List<String>> result = new LinkedHashMap<>();
        if (attributes instanceof List<?> list) {
            for (final Object raw : list) {
                if (!(raw instanceof Map<?, ?> attribute)
                        || !(attribute.get("name") instanceof String id)) {
                    throw new IOException("invalid AACLI attribute entry");
                }
                addAttribute(result, id, attribute.get("values"));
            }
        } else if (attributes instanceof Map<?, ?> map) {
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String id) {
                    addAttribute(result, id, entry.getValue());
                }
            }
        } else {
            throw new IOException("AACLI JSON output does not contain an attributes array");
        }
        return Map.copyOf(result);
    }

    private static void addAttribute(final Map<String, List<String>> result,
            final String id, final Object rawValues) throws IOException {
        if (!id.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("invalid attribute ID in AACLI output");
        }
        if (result.putIfAbsent(id, extractValues(rawValues)) != null) {
            throw new IOException("duplicate attribute in AACLI output: " + id);
        }
    }

    private static Object findAttributes(final Map<?, ?> root) {
        for (final String key : List.of("attributes", "unfilteredAttributes",
                "unfilteredIdPAttributes")) {
            if (root.containsKey(key)) {
                return root.get(key);
            }
        }
        final Object response = root.get("response");
        return response instanceof Map<?, ?> map ? findAttributes(map) : null;
    }

    private static List<String> extractValues(final Object raw) {
        Object source = raw;
        if (raw instanceof Map<?, ?> map) {
            source = map.containsKey("values") ? map.get("values") : map.get("value");
        }
        final List<String> out = new ArrayList<>();
        if (source instanceof List<?> list) {
            for (final Object value : list) {
                final String text = displayValue(value);
                if (text != null) {
                    out.add(text);
                }
            }
        } else {
            final String text = displayValue(source);
            if (text != null) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private static String displayValue(final Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            final Object display = map.get("displayValue");
            return display instanceof String text ? text : null;
        }
        return null;
    }

    private static String safeMessage(final String value) {
        final String oneLine = value.replaceAll("[\\r\\n\\t]+", " ");
        return oneLine.length() <= 512 ? oneLine : oneLine.substring(0, 512);
    }
}
