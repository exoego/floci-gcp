package io.floci.gcp.services.gcs;

import java.nio.charset.StandardCharsets;

/**
 * The URL encoding GCS applies to object names in XML responses: a ListObjects result under
 * {@code encoding-type=url} and the {@code Location} of a completed multipart upload.
 */
final class GcsXmlUrlEncoding {

    // GCS keeps the RFC 3986 query characters except the three that form encoding reserves (probed live on 2026-10-30).
    private static final String KEPT = "-._~!$'()*,;:@/?";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private GcsXmlUrlEncoding() {
    }

    static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 0xff;
            if (b == ' ') {
                out.append('+');
            } else if (isKept(b)) {
                out.append((char) b);
            } else {
                out.append('%').append(HEX[b >>> 4]).append(HEX[b & 0xf]);
            }
        }
        return out.toString();
    }

    private static boolean isKept(int b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || KEPT.indexOf(b) >= 0;
    }
}
