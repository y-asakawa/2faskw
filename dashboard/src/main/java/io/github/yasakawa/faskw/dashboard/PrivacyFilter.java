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
package io.github.yasakawa.faskw.dashboard;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PrivacyFilter {

    public enum UserMode {
        PLAIN,
        DROP,
        HMAC
    }

    public enum IpMode {
        PLAIN,
        DROP,
        PREFIX
    }

    private final UserMode userMode;
    private final IpMode ipMode;
    private final byte[] hmacKey;
    private static final Pattern SAFE_USER_REFERENCE =
            Pattern.compile("[A-Za-z0-9._@-]{1,255}");

    public PrivacyFilter(final UserMode userMode, final IpMode ipMode, final byte[] hmacKey) {
        this.userMode = userMode;
        this.ipMode = ipMode;
        this.hmacKey = hmacKey == null ? null : hmacKey.clone();
        if (userMode == UserMode.HMAC && (hmacKey == null || hmacKey.length < 32)) {
            throw new IllegalArgumentException("user hmac mode requires a key of at least 32 bytes");
        }
    }

    public static UserMode parseUserMode(final String value) {
        return UserMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static IpMode parseIpMode(final String value) {
        return IpMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String userRef(final String user) {
        if (user == null || userMode == UserMode.DROP) {
            return null;
        }
        if (userMode == UserMode.PLAIN) {
            if (!isValidUserReference(user)) {
                throw new IllegalArgumentException("user ID contains invalid characters");
            }
            return user;
        }
        return CryptoSupport.hmacSha256(hmacKey, user);
    }

    public static boolean isValidUserReference(final String value) {
        return value != null && SAFE_USER_REFERENCE.matcher(value).matches();
    }

    public String sourceNetwork(final String ip) {
        if (ip == null || ipMode == IpMode.DROP) {
            return null;
        }
        try {
            final InetAddress address = InetAddress.getByName(ip);
            if (ipMode == IpMode.PLAIN) {
                return address.getHostAddress();
            }
            final byte[] bytes = address.getAddress();
            if (address instanceof Inet6Address) {
                Arrays.fill(bytes, 6, bytes.length, (byte) 0);
                return InetAddress.getByAddress(bytes).getHostAddress() + "/48";
            }
            bytes[3] = 0;
            return InetAddress.getByAddress(bytes).getHostAddress() + "/24";
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
