/* Copyright 2026 Yoshifumi ASAKAWA. Licensed under the Apache License, Version 2.0. */
package io.github.yasakawa.faskw;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GraphicalMatrixCliBootstrapTest {
    @TempDir Path home;
    private String url;
    private static final List<String> IMAGES = List.of("img01", "img02", "img03", "img04");

    @BeforeEach void prepare() throws Exception {
        Files.createDirectories(home.resolve("conf/graphicalmatrix"));
        url = "jdbc:h2:mem:cli-bootstrap-" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        Files.writeString(home.resolve("conf/graphicalmatrix/db.properties"),
            "graphicalmatrix.db.driver=org.h2.Driver\ngraphicalmatrix.db.url=" + url
            + "\ngraphicalmatrix.db.user=sa\ngraphicalmatrix.db.password=\ngraphicalmatrix.db.autoInit=true\n");
        configure("hash");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            GraphicalMatrixSequenceMigrationTool.initSchema(connection);
        }
    }

    private void configure(final String mode) throws Exception {
        Files.writeString(home.resolve("conf/graphicalmatrix/graphicalmatrix.properties"),
            "graphicalmatrix.graphicals=img01-25\ngraphicalmatrix.choice=4\n"
            + "graphicalmatrix.aliases=A:img01,B:img02,C:img03,D:img04\n"
            + "graphicalmatrix.force_sequence_change=0\ngraphicalmatrix.sequence.storage=" + mode + "\n"
            + "graphicalmatrix.sequence.pepper=test-only-pepper\ngraphicalmatrix.sequence.keyword=test-only-keyword\n"
            + "graphicalmatrix.sequence.aesKey=0123456789abcdef\n");
    }

    private void write(final String operation, final String user, final String input) throws Exception {
        final InputStream previous = System.in;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            GraphicalMatrixInitialSequenceTool.run(operation, home.toString(), user);
        } finally { System.setIn(previous); }
    }

    private String field(final String user, final String column) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var query = connection.prepareStatement("SELECT " + column + " FROM graphicalmatrix_enrollment WHERE user_id=?")) {
            query.setString(1, user);
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    @Test void newUserGetsTheSameInitialEvenWhenGlobalForcedChangeIsOff() throws Exception {
        write("current", "alice", "A,B,C,D");
        assertEquals("A,B,C,D", field("alice", "initial_sequence"));
        assertEquals("1", field("alice", "force_sequence_change"));
        assertEquals("ACTIVE", field("alice", "status"));
        assertEquals("1", field("alice", "state_version"));
        assertTrue(field("alice", "sequence").startsWith("hsp1:"));
    }

    @Test void preservesConfiguredCurrentStorageInEveryMode() throws Exception {
        for (final String mode : List.of("hash", "auto", "keyword", "aes-gcm", "plaintext")) {
            configure(mode);
            final String user = "test-" + mode;
            write("current", user, "img01,img02,img03,img04");
            assertEquals("A,B,C,D", field(user, "initial_sequence"));
            final var storage = GraphicalMatrixSequenceStorage.load(home.toString());
            assertTrue(storage.matches(field(user, "sequence"), IMAGES, true, false), mode);
            if (!"plaintext".equals(mode)) { assertNotEquals("A,B,C,D", field(user, "sequence")); }
        }
    }

    @Test void updatingAnExistingUserKeepsTheOriginalInitial() throws Exception {
        write("current", "alice", "A,B,C,D");
        write("current", "alice", "img05,img06,img07,img08");
        assertEquals("A,B,C,D", field("alice", "initial_sequence"));
        assertEquals("2", field("alice", "state_version"));
        assertTrue(GraphicalMatrixSequenceStorage.load(home.toString()).matches(field("alice", "sequence"),
            List.of("img05", "img06", "img07", "img08"), true, false));
    }

    @Test void doesNotSilentlyFillAnExistingUnsetOrLegacyInitial() throws Exception {
        write("current", "alice", "A,B,C,D");
        for (final String initial : List.of("", "hsp1:4:legacy:legacy")) {
            try (var connection = DriverManager.getConnection(url, "sa", "");
                    var query = connection.prepareStatement("UPDATE graphicalmatrix_enrollment SET initial_sequence=? WHERE user_id='alice'")) {
                query.setString(1, initial); query.executeUpdate();
            }
            write("current", "alice", "img05,img06,img07,img08");
            assertEquals(initial, field("alice", "initial_sequence"));
        }
    }

    @Test void freshUserCanResetAndCannotDisableBootstrapChange() throws Exception {
        write("current", "alice", "A,B,C,D");
        assertThrows(IllegalStateException.class, () -> write("allow-change-off", "alice", ""));
        write("current", "alice", "img05,img06,img07,img08");
        write("reset", "alice", "");
        assertEquals("A,B,C,D", field("alice", "initial_sequence"));
        assertEquals("1", field("alice", "force_sequence_change"));
        assertTrue(GraphicalMatrixSequenceStorage.load(home.toString()).matches(field("alice", "sequence"), IMAGES, true, false));
    }

    @Test void invalidInputNeverCreatesAPartialUser() throws Exception {
        for (final String input : List.of("", "A,B,C", "A,B,C,unknown", "A,A,C,D")) {
            assertThrows(Exception.class, () -> write("current", "alice", input));
            assertNull(field("alice", "initial_sequence"));
        }
    }
}
