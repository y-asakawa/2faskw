package io.github.yasakawa.faskw.plugin;

import java.io.IOException;

import net.shibboleth.idp.module.PropertyDrivenIdPModule;
import net.shibboleth.profile.module.ModuleException;

public final class GraphicalMatrixModule extends PropertyDrivenIdPModule {
    public GraphicalMatrixModule() throws IOException, ModuleException {
        super(GraphicalMatrixModule.class);
    }
}
