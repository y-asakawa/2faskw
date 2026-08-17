/*
 * Copyright 2026 Yoshifumi ASAKAWA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.yasakawa.faskw;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

public final class GraphicalMatrixH2Command {
    private GraphicalMatrixH2Command() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 4 || !("sql".equals(args[0]) || "script".equals(args[0]))) {
            throw new IllegalArgumentException(
                "usage: GraphicalMatrixH2Command sql|script JDBC_URL USER SQL_OR_FILE");
        }
        final String password = readPassword();
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection(args[1], args[2], password)) {
            if ("sql".equals(args[0])) {
                runSql(connection, args[3]);
            } else {
                runScript(connection, Path.of(args[3]));
            }
        }
    }

    private static String readPassword() throws Exception {
        final BufferedReader input = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final String password = input.readLine();
        if (password == null) {
            throw new IllegalArgumentException("H2 password was not provided on standard input");
        }
        return password;
    }

    private static void runSql(final Connection connection, final String sql) throws Exception {
        final Class<?> shellClass = Class.forName("org.h2.tools.Shell");
        final Object shell = shellClass.getConstructor().newInstance();
        invoke(shellClass.getMethod("runTool", Connection.class, String[].class), shell,
            connection, new String[] {"-sql", sql});
    }

    private static void runScript(final Connection connection, final Path script) throws Exception {
        final Class<?> runScriptClass = Class.forName("org.h2.tools.RunScript");
        try (Reader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
            final Object result = invoke(runScriptClass.getMethod(
                "execute", Connection.class, Reader.class), null, connection, reader);
            if (result instanceof ResultSet resultSet) {
                resultSet.close();
            }
        }
    }

    private static Object invoke(final Method method, final Object target,
            final Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException ex) {
            final Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw ex;
        }
    }
}
