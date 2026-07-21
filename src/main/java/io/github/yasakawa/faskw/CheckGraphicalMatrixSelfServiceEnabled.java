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
