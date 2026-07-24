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

package io.github.yasakawa.faskw.plugin;

import java.io.IOException;

import net.shibboleth.idp.module.PropertyDrivenIdPModule;
import net.shibboleth.profile.module.ModuleException;

public final class GraphicalMatrixModule extends PropertyDrivenIdPModule {
    public GraphicalMatrixModule() throws IOException, ModuleException {
        super(GraphicalMatrixModule.class);
    }
}
