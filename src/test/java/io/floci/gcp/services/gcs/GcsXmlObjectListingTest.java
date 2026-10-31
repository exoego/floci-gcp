package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GcsXmlObjectListingTest {

    @Test
    void continuationTokenIsTheLastKeyAsABase64ProtobufStringField() {
        // Issued by GCS on 2026-10-30 for a page that ended on this key.
        String token = "CiFmbG9jaS1wcm9iZS1saXN0LTE3OTMzMzQ2NzEvYi50eHQ=";
        assertEquals(token, GcsXmlObjectListing.token("floci-probe-list-1793334671/b.txt"));
        assertEquals("floci-probe-list-1793334671/b.txt", GcsXmlObjectListing.keyOf(token));
    }

    @Test
    void continuationTokenRoundTripsALongKey() {
        String key = "dir/" + "k".repeat(300);
        assertEquals(key, GcsXmlObjectListing.keyOf(GcsXmlObjectListing.token(key)));
    }

    @Test
    void aTokenTheEmulatorDidNotIssueIsAnInvalidArgument() {
        for (String bad : new String[] {"not-a-token", "", "Cg==", "CgViLnR4", "AQ=="}) {
            GcpException error = assertThrows(GcpException.class, () -> GcsXmlObjectListing.keyOf(bad), bad);
            assertEquals(400, error.getHttpStatus(), bad);
            assertEquals("Invalid page token.", error.getMessage(), bad);
        }
    }
}
