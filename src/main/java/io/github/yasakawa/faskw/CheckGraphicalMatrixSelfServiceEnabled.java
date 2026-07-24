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

import org.opensaml.profile.action.ActionSupport;
import org.opensaml.profile.action.EventIds;
import org.opensaml.profile.context.ProfileRequestContext;

import net.shibboleth.idp.profile.AbstractProfileAction;

/** Rejects the administrative flow unless self-service is explicitly enabled. */
public final class CheckGraphicalMatrixSelfServiceEnabled extends AbstractProfileAction {
    @Override
    protected void doExecute(final ProfileRequestContext profileRequestContext) {
        try {
            final GraphicalMatrixConfig config =
                GraphicalMatrixConfig.load(GraphicalMatrixRuntime.idpHome());
            if (!config.isSelfServiceEnabled()) {
                ActionSupport.buildEvent(profileRequestContext, EventIds.ACCESS_DENIED);
            }
        } catch (Exception ex) {
            ActionSupport.buildEvent(profileRequestContext, EventIds.ACCESS_DENIED);
        }
    }
}
