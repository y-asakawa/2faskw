package io.github.yasakawa.faskw;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import net.shibboleth.idp.plugin.authn.totp.context.TOTPContext;
import net.shibboleth.idp.plugin.authn.totp.impl.AbstractSeedSource;
import net.shibboleth.shared.codec.Base32Support;
import org.opensaml.profile.context.ProfileRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GraphicalMatrixTotpSeedSource extends AbstractSeedSource {
    private static final Logger LOG = LoggerFactory.getLogger(GraphicalMatrixTotpSeedSource.class);

    private final String idpHome;
    private final GraphicalMatrixTotpSeedStorage seedStorage;

    public GraphicalMatrixTotpSeedSource() {
        this.idpHome = System.getProperty("idp.home", "/opt/shibboleth-idp");
        this.seedStorage = GraphicalMatrixTotpSeedStorage.load(idpHome);
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
            try (Connection c = GraphicalMatrixDataSource.getConnection(idpHome);
                    PreparedStatement ps = c.prepareStatement(
                        "SELECT totp_seed FROM graphicalmatrix_enrollment "
                        + "WHERE user_id = ? AND status = 'ACTIVE' "
                        + "AND UPPER(mfa_method) IN ('TOTP', 'MFA:TOTP') "
                        + "AND totp_status = 'ACTIVE' "
                        + "AND totp_seed IS NOT NULL AND totp_seed <> ''")) {
                ps.setString(1, user);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return seedStorage.decode(rs.getString("totp_seed"));
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warn("TOTP seed DB lookup failed for user={}: {}", user, ex.toString());
        }
        return "";
    }

    private static String trim(final String value) {
        return value != null ? value.trim() : "";
    }
}
