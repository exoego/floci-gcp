package io.floci.gcp.services.gcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GcsXmlUrlEncodingTest {

    @Test
    void encodesAnObjectNameTheWayGcsDoes() {
        // The name and its encoding come from a live ListObjects with encoding-type=url on 2026-10-30.
        String name = "x!\"#$%&'()*+,-.:;<=>?@[\\]^_`{|}~ 日本.txt";
        assertEquals("x!%22%23$%25%26'()*%2B,-.:;%3C%3D%3E?@%5B%5C%5D%5E_%60%7B%7C%7D~+%E6%97%A5%E6%9C%AC.txt",
                GcsXmlUrlEncoding.encode(name));
    }

    @Test
    void keepsTheSlashAndTurnsASpaceIntoAPlus() {
        assertEquals("d1/sp+ace%2Bplus.bin", GcsXmlUrlEncoding.encode("d1/sp ace+plus.bin"));
        assertEquals("", GcsXmlUrlEncoding.encode(""));
    }
}
