package io.github.yasakawa.faskw;

public final class GraphicalMatrixTotpSeedTool {
    private GraphicalMatrixTotpSeedTool() {
    }

    public static void main(final String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }

        final String command = args[0];
        final String idpHome = args[1];
        try {
            final GraphicalMatrixTotpSeedStorage storage = GraphicalMatrixTotpSeedStorage.load(idpHome);
            if ("mode".equals(command)) {
                System.out.println(storage.mode());
                return;
            }
            if ("encode".equals(command)) {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                System.out.println(storage.encode(args[2]));
                return;
            }
            if ("decode".equals(command)) {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                System.out.println(storage.decode(args[2]));
                return;
            }
            if ("storage".equals(command)) {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                System.out.println(storage.storedMode(args[2]));
                return;
            }
            usage();
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  GraphicalMatrixTotpSeedTool mode IDP_HOME");
        System.err.println("  GraphicalMatrixTotpSeedTool encode IDP_HOME BASE32SEED");
        System.err.println("  GraphicalMatrixTotpSeedTool decode IDP_HOME STORED_SEED");
        System.err.println("  GraphicalMatrixTotpSeedTool storage IDP_HOME STORED_SEED");
    }
}
