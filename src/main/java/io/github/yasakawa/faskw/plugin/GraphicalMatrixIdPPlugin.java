package io.github.yasakawa.faskw.plugin;

import java.io.IOException;

import net.shibboleth.idp.plugin.PropertyDrivenIdPPlugin;
import net.shibboleth.profile.plugin.PluginException;

public final class GraphicalMatrixIdPPlugin extends PropertyDrivenIdPPlugin {
    public GraphicalMatrixIdPPlugin() throws IOException, PluginException {
        super(GraphicalMatrixIdPPlugin.class);
    }
}
