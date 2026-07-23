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

import net.shibboleth.idp.plugin.authn.totp.context.TOTPContext;
import net.shibboleth.idp.plugin.authn.totp.impl.AbstractSeedSource;
import net.shibboleth.shared.codec.Base32Support;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GraphicalMatrixTotpSeedSource extends AbstractSeedSource {
    private static final Logger LOG = LoggerFactory.getLogger(GraphicalMatrixTotpSeedSource.class);

    private final String idpHome;

    public GraphicalMatrixTotpSeedSource() {
        this.idpHome = System.getProperty("idp.home", "/opt/shibboleth-idp");
    }

    @Override
    public void accept(final ProfileRequestContext input) {
        checkComponentActive();

        final TOTPContext totpCtx = getTOTPContextLookupStrategy().apply(input);
        if (totpCtx == null) {
            LOG.warn("Unable to locate TOTPContext");
            return;
        }

        final String user = trim(totpCtx.getUsername());
        if (user.isEmpty()) {
            LOG.warn("TOTPContext did not contain a username");
            return;
        }

        final String seed = findSeed(user);
        if (seed.isEmpty()) {
            LOG.warn("TOTP seed was not found for user={}", user);
            return;
        }

        try {
            totpCtx.getTokenSeeds().add(Base32Support.decode(seed));
        } catch (Exception ex) {
            LOG.error("Unable to decode TOTP seed for user={}", user, ex);
        }
    }

    private String findSeed(final String user) {
        try {
            return new GraphicalMatrixRepository(idpHome).findActiveTotpSeed(user);
        } catch (Exception ex) {
            LOG.warn("TOTP seed lookup failed for user={}: {}", user, ex.toString());
        }
        return "";
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
