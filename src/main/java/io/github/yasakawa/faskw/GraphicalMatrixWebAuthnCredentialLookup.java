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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;

import jakarta.servlet.ServletContext;

/** Looks up existing credentials without making the WebAuthn Plugin mandatory. */
final class GraphicalMatrixWebAuthnCredentialLookup {
    private static final String ROOT_APPLICATION_CONTEXT =
        "org.springframework.web.context.WebApplicationContext.ROOT";
    private static final String CUSTOM_REPOSITORY =
        "shibboleth.authn.WebAuthn.CredentialRepository";
    private static final String DEFAULT_REPOSITORY =
        "shibboleth.authn.WebAuthn.DefaultCredentialRepository";

    enum Result {
        PRESENT,
        ABSENT,
        UNAVAILABLE
    }

    private GraphicalMatrixWebAuthnCredentialLookup() {
    }

    static Result lookup(final ServletContext servletContext, final String user) {
        if (servletContext == null || user == null || user.isBlank()) {
            return Result.UNAVAILABLE;
        }
        final Object applicationContext = servletContext.getAttribute(ROOT_APPLICATION_CONTEXT);
        if (applicationContext == null) {
            return Result.UNAVAILABLE;
        }

        final Object repository = bean(applicationContext, CUSTOM_REPOSITORY,
            DEFAULT_REPOSITORY);
        if (repository == null) {
            return Result.UNAVAILABLE;
        }
        try {
            final Method lookup = repository.getClass()
                .getMethod("getRegistrationsByUsername", String.class);
            final Object registrations = lookup.invoke(repository, user);
            if (registrations instanceof Collection<?> collection) {
                return collection.isEmpty() ? Result.ABSENT : Result.PRESENT;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            servletContext.log("2FAS-KW could not query the WebAuthn CredentialRepository", ex);
        }
        return Result.UNAVAILABLE;
    }

    private static Object bean(final Object applicationContext, final String... names) {
        final Method getBean;
        try {
            getBean = applicationContext.getClass().getMethod("getBean", String.class);
        } catch (NoSuchMethodException | SecurityException ex) {
            return null;
        }
        for (String name : names) {
            try {
                final Object candidate = getBean.invoke(applicationContext, name);
                if (candidate != null) {
                    return candidate;
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ex) {
                // Try the standard repository when no deployer-supplied bean exists.
            }
        }
        return null;
    }
}
