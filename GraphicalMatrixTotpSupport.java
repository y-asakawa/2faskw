package io.github.yasakawa.faskw.graphicalmatrix;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import net.shibboleth.shared.codec.Base32Support;

import java.util.EnumMap;
import java.util.Map;

public final class GraphicalMatrixTotpSupport {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOTP_DIGITS = 6;
    private static final long TOTP_STEP_MILLIS = 30_000L;

    private GraphicalMatrixTotpSupport() {
    }

    public static String newBase32Seed() throws Exception {
        final byte[] seed = new byte[20];
        RNG.nextBytes(seed);
        return Base32Support.encode(seed, Base32Support.UNCHUNKED).replace("=", "");
    }

    public static String otpauthUrl(final String issuer, final String account, final String seed) {
        final String safeIssuer = issuer != null && !issuer.isBlank() ? issuer : "ShinshuIDP";
        final String label = safeIssuer + ":" + account;
        return "otpauth://totp/" + url(label)
            + "?secret=" + url(seed)
            + "&issuer=" + url(safeIssuer)
            + "&digits=6&period=30";
    }

    public static String qrSvg(final String value, final int moduleSize, final int quietZone) throws Exception {
        final Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, Integer.valueOf(quietZone));

        final BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints);
        final int width = matrix.getWidth() * moduleSize;
        final int height = matrix.getHeight() * moduleSize;
        final StringBuilder svg = new StringBuilder(4096);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
            .append(width).append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
            .append(width).append(' ').append(height).append("\">");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>");
        svg.append("<path fill=\"#111827\" d=\"");
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    final int px = x * moduleSize;
                    final int py = y * moduleSize;
                    svg.append('M').append(px).append(' ').append(py)
                        .append('h').append(moduleSize)
                        .append('v').append(moduleSize)
                        .append('h').append(-moduleSize).append('z');
                }
            }
        }
        svg.append("\"/></svg>");
        return svg.toString();
    }

    public static boolean verify(final String base32Seed, final String code, final long nowMillis,
            final int window) {
        final String normalizedCode = code != null ? code.trim() : "";
        if (!normalizedCode.matches("[0-9]{6}")) {
            return false;
        }

        try {
            final byte[] key = Base32Support.decode(base32Seed);
            final long step = nowMillis / TOTP_STEP_MILLIS;
            for (int offset = -window; offset <= window; offset++) {
                if (normalizedCode.equals(generateCode(key, step + offset))) {
                    return true;
                }
            }
        } catch (Exception ex) {
            return false;
        }
        return false;
    }

    private static String generateCode(final byte[] key, final long step) throws Exception {
        final byte[] counter = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }

        final Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        final byte[] hash = mac.doFinal(counter);
        final int offset = hash[hash.length - 1] & 0x0f;
        final int binary = ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
        final int otp = binary % 1_000_000;
        return String.format("%0" + TOTP_DIGITS + "d", Integer.valueOf(otp));
    }

    private static String url(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
