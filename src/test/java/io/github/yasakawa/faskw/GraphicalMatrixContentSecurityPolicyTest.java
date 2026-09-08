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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

final class GraphicalMatrixContentSecurityPolicyTest {
    private static final Pattern INLINE_CONTENT = Pattern.compile(
        "<script\\b(?![^>]*\\bsrc\\s*=)|<style\\b|\\sstyle\\s*=|\\son[a-z]+\\s*=|javascript\\s*:",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void bundledTemplatesContainNoInlineExecutableContent() throws Exception {
        try (var paths = Files.list(Path.of("views"))) {
            for (final Path path : paths.filter(value -> value.toString().endsWith(".html")).toList()) {
                final String template = Files.readString(path);
                assertFalse(INLINE_CONTENT.matcher(template).find(), path.toString());
            }
        }
    }

    @Test
    void selectionTemplatesLoadOnlyTheExternalScriptPlaceholder() throws Exception {
        for (final String name : new String[] {"graphicalmatrix.html", "change-current.html", "change-new.html"}) {
            final String template = Files.readString(Path.of("views", name));
            assertTrue(template.contains("{{scriptLink}}"), name);
            assertFalse(template.contains("{{scriptBlock}}"), name);
        }
    }

    @Test
    void bundledJavascriptAvoidsHtmlStringInjectionSinks() throws Exception {
        final String javascript = Files.readString(Path.of("assets/graphicalmatrix.js"));

        assertFalse(javascript.contains("innerHTML"));
        assertFalse(javascript.contains("outerHTML"));
        assertFalse(javascript.contains("document.write"));
        assertFalse(javascript.contains("eval("));
        assertFalse(javascript.contains("new Function"));
    }
}
