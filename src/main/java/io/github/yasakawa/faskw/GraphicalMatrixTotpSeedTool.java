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
