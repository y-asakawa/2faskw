package io.github.yasakawa.faskw;

import java.util.List;

public final class GraphicalMatrixSequenceTool {
    private GraphicalMatrixSequenceTool() {
    }

    public static void main(final String[] args) {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }

        final String command = args[0];
        final String idpHome = args[1];
        try {
            final GraphicalMatrixSequenceStorage storage = GraphicalMatrixSequenceStorage.load(idpHome);
            if ("mode".equals(command)) {
                System.out.println(storage.mode());
                return;
            }
            if ("encode".equals(command)) {
                if (args.length < 5) {
                    usage();
                    System.exit(2);
                }
                final List<String> sequence = GraphicalMatrixSupport.csv(args[2]);
                final boolean ordered = Boolean.parseBoolean(args[3]);
                final boolean duplicates = Boolean.parseBoolean(args[4]);
                System.out.println(storage.encode(sequence, ordered, duplicates));
                return;
            }
            if ("display".equals(command)) {
                if (args.length < 3) {
                    usage();
                    System.exit(2);
                }
                System.out.println(String.join(",", storage.displayTokens(args[2])));
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
            if ("matches".equals(command)) {
                if (args.length < 6) {
                    usage();
                    System.exit(2);
                }
                final List<String> selected = GraphicalMatrixSupport.csv(args[3]);
                final boolean ordered = Boolean.parseBoolean(args[4]);
                final boolean duplicates = Boolean.parseBoolean(args[5]);
                System.out.println(storage.matches(args[2], selected, ordered, duplicates));
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
        System.err.println("  GraphicalMatrixSequenceTool mode IDP_HOME");
        System.err.println("  GraphicalMatrixSequenceTool encode IDP_HOME sequenceCsv ordered duplicates");
        System.err.println("  GraphicalMatrixSequenceTool display IDP_HOME storedSequence");
        System.err.println("  GraphicalMatrixSequenceTool storage IDP_HOME storedSequence");
        System.err.println("  GraphicalMatrixSequenceTool matches IDP_HOME storedSequence selectedCsv ordered duplicates");
    }
}
