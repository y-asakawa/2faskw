package io.github.yasakawa.faskw;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

public final class GraphicalMatrixDataSourceListener implements ServletContextListener {
    @Override
    public void contextDestroyed(final ServletContextEvent event) {
        GraphicalMatrixDataSource.close();
    }
}
