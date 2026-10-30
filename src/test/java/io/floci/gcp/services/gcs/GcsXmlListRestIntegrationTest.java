package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XML API listing: the list-type 1 and 2 result shapes, pagination, and the gzip refusal that
 * decides transcoding.
 */
@QuarkusTest
class GcsXmlListRestIntegrationTest {

    private static final String BUCKET = "xml-list-bucket";
    private static final String RESULT_START = "<ListBucketResult xmlns=\"http://doc.s3.amazonaws.com/2006-03-01\">";
    // The token GCS issues for a page that ended on b.txt.
    private static final String TOKEN_B = "CgViLnR4dA==";

    private static String newBucket(String tag) {
        String name = "xml-list-" + tag + "-" + System.nanoTime();
        given().contentType("application/json").body("{\"name\":\"" + name + "\"}")
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        return name;
    }

    private static void put(String bucket, String... names) {
        for (String name : names) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + bucket + "/o").then().statusCode(200);
        }
    }

    @Test
    void listingFollowsTheVersionOneShape() {
        String bucket = newBucket("v1");
        put(bucket, "a.txt", "d1/x.txt", "d1/y.txt", "z.txt");
        Response response = given().queryParam("delimiter", "/").when().get("/" + bucket);
        String body = response.then().statusCode(200).extract().asString();
        String metageneration = given().get("/storage/v1/b/" + bucket).then().statusCode(200)
                .extract().jsonPath().getString("metageneration");
        assertEquals(metageneration, response.header("x-goog-metageneration"));
        // No MaxKeys without max-keys, objects before prefixes, no StorageClass.
        assertTrue(body.startsWith(RESULT_START + "<Name>" + bucket + "</Name><Prefix></Prefix><Marker></Marker>"
                + "<Delimiter>/</Delimiter><IsTruncated>false</IsTruncated><Contents><Key>a.txt</Key>"), body);
        assertTrue(body.matches(".*<Contents><Key>z\\.txt</Key><Generation>\\d+</Generation><MetaGeneration>1</MetaGeneration>"
                + "<LastModified>[^<]+</LastModified><ETag>[^<]+</ETag><Size>1</Size></Contents>"
                + "<CommonPrefixes><Prefix>d1/</Prefix></CommonPrefixes></ListBucketResult>"), body);
        assertFalse(body.contains("<MaxKeys>"), body);
        assertFalse(body.contains("<StorageClass>"), body);
    }

    @Test
    void maxKeysIsEchoedOnlyWhenSent() {
        String bucket = newBucket("maxkeys");
        put(bucket, "a.txt", "b.txt");
        given().queryParam("max-keys", 1).when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Marker></Marker><NextMarker>a.txt</NextMarker><MaxKeys>1</MaxKeys>"
                        + "<IsTruncated>true</IsTruncated><Contents><Key>a.txt</Key>"));
        // GCS answers max-keys=0 with an empty page that is not truncated.
        given().queryParam("max-keys", 0).when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Marker></Marker><MaxKeys>0</MaxKeys><IsTruncated>false</IsTruncated></ListBucketResult>"));
        given().queryParam("max-keys", -1).when().get("/" + bucket).then().statusCode(200)
                .body(not(containsString("<MaxKeys>")))
                .body(containsString("<Key>b.txt</Key>"));
        given().queryParam("max-keys", "abc").when().get("/" + bucket).then().statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Message", equalTo("Invalid argument."))
                .body("Error.Details", equalTo("Invalid max-keys: abc"));
    }

    @Test
    void versionTwoUsesKeyCountAndContinuationTokens() {
        String bucket = newBucket("v2");
        put(bucket, "a.txt", "b.txt", "d1/x.txt", "d2/y.txt", "z.txt");
        String first = given().queryParam("list-type", 2).queryParam("delimiter", "/").queryParam("max-keys", 2)
                .when().get("/" + bucket).then().statusCode(200).extract().asString();
        assertTrue(first.startsWith(RESULT_START + "<Name>" + bucket + "</Name><Prefix></Prefix>"
                + "<NextContinuationToken>" + TOKEN_B + "</NextContinuationToken><KeyCount>2</KeyCount><MaxKeys>2</MaxKeys>"
                + "<Delimiter>/</Delimiter><IsTruncated>true</IsTruncated><Contents><Key>a.txt</Key>"), first);
        assertFalse(first.contains("<Marker>"), first);

        String second = given().queryParam("list-type", 2).queryParam("delimiter", "/").queryParam("max-keys", 2)
                .queryParam("continuation-token", TOKEN_B)
                .when().get("/" + bucket).then().statusCode(200).extract().asString();
        // KeyCount counts objects, so a page of prefixes only counts none.
        assertTrue(second.contains("<Prefix></Prefix><ContinuationToken>" + TOKEN_B + "</ContinuationToken><NextContinuationToken>"), second);
        assertTrue(second.endsWith("</NextContinuationToken><KeyCount>0</KeyCount><MaxKeys>2</MaxKeys><Delimiter>/</Delimiter>"
                + "<IsTruncated>true</IsTruncated><CommonPrefixes><Prefix>d1/</Prefix></CommonPrefixes>"
                + "<CommonPrefixes><Prefix>d2/</Prefix></CommonPrefixes></ListBucketResult>"), second);

        String next = second.replaceAll(".*<NextContinuationToken>([^<]+)</NextContinuationToken>.*", "$1");
        given().queryParam("list-type", 2).queryParam("delimiter", "/").queryParam("max-keys", 2)
                .queryParam("continuation-token", next)
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<KeyCount>1</KeyCount><MaxKeys>2</MaxKeys><Delimiter>/</Delimiter>"
                        + "<IsTruncated>false</IsTruncated><Contents><Key>z.txt</Key>"))
                .body(not(containsString("<NextContinuationToken>")));
    }

    @Test
    void versionTwoEchoesStartAfterAndPrefersAToken() {
        String bucket = newBucket("v2after");
        put(bucket, "a.txt", "b.txt", "c.txt");
        given().queryParam("list-type", 2).queryParam("start-after", "a.txt")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Prefix></Prefix><StartAfter>a.txt</StartAfter><KeyCount>2</KeyCount>"
                        + "<IsTruncated>false</IsTruncated><Contents><Key>b.txt</Key>"));
        given().queryParam("list-type", 2).queryParam("start-after", "a.txt").queryParam("continuation-token", TOKEN_B)
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<StartAfter>a.txt</StartAfter><ContinuationToken>" + TOKEN_B + "</ContinuationToken>"
                        + "<KeyCount>1</KeyCount><IsTruncated>false</IsTruncated><Contents><Key>c.txt</Key>"));
    }

    @Test
    void versionTwoRejectsAMarkerAndATokenItDidNotIssue() {
        String bucket = newBucket("v2bad");
        given().queryParam("list-type", 2).queryParam("marker", "a.txt")
                .when().get("/" + bucket).then().statusCode(400)
                .body("Error.Code", equalTo("NotImplemented"))
                .body("Error.Message", equalTo("A header or query you provided requested a function that is not implemented."))
                .body("Error.Details", equalTo("GET ?marker is not implemented for listObjectsV2"));
        given().queryParam("list-type", 2).queryParam("continuation-token", "not-a-token")
                .when().get("/" + bucket).then().statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Message", equalTo("Invalid argument."))
                .body("Error.Details", equalTo("Invalid page token."));
    }

    @Test
    void listTypeOtherThanTwoIsVersionOne() {
        String bucket = newBucket("v3");
        put(bucket, "a.txt");
        given().queryParam("list-type", 3).when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Prefix></Prefix><Marker></Marker><IsTruncated>false</IsTruncated><Contents>"))
                .body(not(containsString("<KeyCount>")));
    }

    @Test
    void urlEncodingAppliesToEveryEchoedName() {
        String bucket = newBucket("enc");
        put(bucket, "sp ace+1.txt", "sp ace+2.txt", "sp dir+x/y.txt");
        given().queryParam("prefix", "sp ").queryParam("delimiter", "/").queryParam("marker", "sp ace+1.txt")
                .queryParam("max-keys", 1).queryParam("encoding-type", "url")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Prefix>sp+</Prefix><Marker>sp+ace%2B1.txt</Marker><NextMarker>sp+ace%2B2.txt</NextMarker>"
                        + "<MaxKeys>1</MaxKeys><Delimiter>/</Delimiter><IsTruncated>true</IsTruncated><Encoding-Type>url</Encoding-Type>"
                        + "<Contents><Key>sp+ace%2B2.txt</Key>"));
        given().queryParam("prefix", "sp ").queryParam("delimiter", "+").queryParam("encoding-type", "URL")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Delimiter>%2B</Delimiter><IsTruncated>false</IsTruncated><Encoding-Type>url</Encoding-Type>"
                        + "<CommonPrefixes><Prefix>sp+ace%2B</Prefix></CommonPrefixes>"
                        + "<CommonPrefixes><Prefix>sp+dir%2B</Prefix></CommonPrefixes></ListBucketResult>"));
        given().queryParam("list-type", 2).queryParam("prefix", "sp ").queryParam("start-after", "sp ace+1.txt")
                .queryParam("encoding-type", "url")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Prefix>sp+</Prefix><StartAfter>sp+ace%2B1.txt</StartAfter><KeyCount>2</KeyCount>"
                        + "<IsTruncated>false</IsTruncated><EncodingType>url</EncodingType><Contents><Key>sp+ace%2B2.txt</Key>"))
                .body(containsString("<Contents><Key>sp+dir%2Bx/y.txt</Key>"));
        given().queryParam("encoding-type", "foo").when().get("/" + bucket).then().statusCode(400)
                .body("Error.Code", equalTo("InvalidArgument"))
                .body("Error.Details", equalTo("Invalid encoding-type: foo"));
    }

    private static void seed() {
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        for (String name : new String[] {"a.txt", "b.txt", "c.txt"}) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
    }

    @Test
    void markerStartsListingAfterTheGivenKey() {
        // "Objects whose names are lexicographically greater than the marker are returned"
        // (https://cloud.google.com/storage/docs/xml-api/get-bucket-list). Without marker
        // support a client that pages on IsTruncated re-reads the first page forever.
        seed();
        given().queryParam("marker", "a.txt")
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(not(containsString("<Key>a.txt</Key>")))
                .body(containsString("<Key>b.txt</Key>"))
                .body(containsString("<Key>c.txt</Key>"))
                .body(containsString("<Marker>a.txt</Marker>"));
    }

    @Test
    void truncatedListingCarriesTheNextMarker() {
        seed();
        given().queryParam("max-keys", 1)
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .body(containsString("<NextMarker>a.txt</NextMarker>"));
    }

    @Test
    void pagingWithTheNextMarkerTerminates() {
        seed();
        String second = given().queryParam("max-keys", 1).queryParam("marker", "a.txt")
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .extract().asString();
        // The second page must move on rather than repeating the first.
        org.junit.jupiter.api.Assertions.assertTrue(second.contains("<Key>b.txt</Key>"), second);
        org.junit.jupiter.api.Assertions.assertFalse(second.contains("<Key>a.txt</Key>"), second);
    }

    @Test
    void gzipRefusedWithAZeroQualityIsNotServedAsGzip() {
        // RFC 7231 writes a qvalue as "0" [ "." 0*3DIGIT ], so q=0.0 is the same refusal as q=0.
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("text/plain").body("plain-bytes")
                .queryParam("uploadType", "media").queryParam("name", "q0.txt")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);

        given().header("Accept-Encoding", "gzip;q=0.0")
                .when().get("/storage/v1/b/" + BUCKET + "/o/q0.txt?alt=media")
                .then().statusCode(200)
                .header("Content-Encoding", (String) null);
    }

    @Test
    void maxKeysBoundsObjectsAndPrefixesTogether() {
        // A page is one run over both, so a delimiter listing cannot emit unlimited prefixes
        // alongside a capped set of objects, and the NextMarker has to name whichever entry
        // ended the page or the next request repeats them.
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        for (String name : new String[] {"d1/x.txt", "d2/x.txt", "d3/x.txt"}) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
        String first = given().queryParam("delimiter", "/").queryParam("max-keys", 1)
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertTrue(first.contains("<NextMarker>"), first);
        // Exactly one entry on the page, not one object plus every prefix.
        org.junit.jupiter.api.Assertions.assertEquals(1,
                first.split("<CommonPrefixes>", -1).length - 1 + (first.split("<Contents>", -1).length - 1),
                first);
    }

    @Test
    void explicitGzipRefusalBeatsAWildcard() {
        // "gzip;q=0, *" refuses gzip: the specific entry decides (RFC 7231 section 5.3.4).
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("text/plain").body("plain")
                .queryParam("uploadType", "media").queryParam("name", "wc.txt")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);
        given().header("Accept-Encoding", "gzip;q=0, *")
                .when().get("/storage/v1/b/" + BUCKET + "/o/wc.txt?alt=media")
                .then().statusCode(200)
                .header("Content-Encoding", (String) null);
    }

    @Test
    void pagingFromAPrefixMarkerAdvancesPastThatPrefix() {
        // A NextMarker naming a common prefix has to move the listing past every object under
        // it; comparing against raw object names would re-roll the same prefix forever.
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\"}")
                .when().post("/storage/v1/b?project=test-project");
        for (String name : new String[] {"p1/a.txt", "p1/b.txt", "p2/a.txt"}) {
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media").queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
        given().queryParam("delimiter", "/").queryParam("marker", "p1/")
                .when().get("/" + BUCKET)
                .then().statusCode(200)
                .body(not(containsString("<Prefix>p1/</Prefix>")))
                .body(containsString("<Prefix>p2/</Prefix>"));
    }
}
