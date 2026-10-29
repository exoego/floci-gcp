package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The XML API names an object by one entity tag on PUT, GET, HEAD and ListObjects, in quotes
 * (probed live on 2026-10-29): the MD5 in hex for a plain object, the MD5 of the part digests
 * with a dash and the part count for a multipart upload, and a dash before the resource etag
 * for a composite object. The JSON API keeps its own resource etag.
 */
@QuarkusTest
class GcsXmlEtagRestIntegrationTest {

    private static final String PLAIN_ETAG = "\"6f5902ac237024bdd0c176cb93063dc4\"";

    private String bucket;

    @BeforeEach
    void createBucket() {
        bucket = "xml-etag-" + System.nanoTime();
        given().contentType("application/json").queryParam("project", "test-project")
                .body(Map.of("name", bucket)).post("/storage/v1/b").then().statusCode(200);
    }

    @Test
    void plainObjectCarriesItsMd5InHexOnEveryXmlRoute() {
        putPlain("plain.txt", "hello world\n");

        assertXmlEtag("plain.txt", PLAIN_ETAG);

        String resourceEtag = given().when().get("/storage/v1/b/{bucket}/o/plain.txt", bucket)
                .then().statusCode(200).extract().path("etag");
        given().when().get("/storage/v1/b/{bucket}/o/plain.txt?alt=media", bucket)
                .then().statusCode(200)
                .header("ETag", equalTo(resourceEtag));
    }

    @Test
    void compositeObjectCarriesADashAndItsResourceEtag() {
        putPlain("a.txt", "hello ");
        putPlain("b.txt", "world\n");
        String resourceEtag = given().contentType("application/json")
                .body("{\"sourceObjects\":[{\"name\":\"a.txt\"},{\"name\":\"b.txt\"}]}")
                .when().post("/storage/v1/b/{bucket}/o/composed.bin/compose", bucket)
                .then().statusCode(200)
                .body("componentCount", equalTo(2))
                .body("md5Hash", nullValue())
                .extract().path("etag");

        assertXmlEtag("composed.bin", "\"-" + resourceEtag + "\"");
    }

    @Test
    void multipartObjectCarriesTheMd5OfItsPartDigestsAndThePartCount() {
        String path = "/" + bucket + "/multipart.bin";
        String uploadId = initiate(path);
        String partEtag = given().body("hello world\n".getBytes(StandardCharsets.UTF_8))
                .put(path + "?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200)
                .header("ETag", equalTo(PLAIN_ETAG))
                .extract().header("ETag");

        Response completed = given().contentType("application/xml")
                .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + partEtag
                        + "</ETag></Part></CompleteMultipartUpload>")
                .post(path + "?uploadId=" + uploadId);
        completed.then().statusCode(200)
                .header("ETag", equalTo("\"8d780cfbcd9622aa77011564e615065f-1\""));
        assertEquals("\"8d780cfbcd9622aa77011564e615065f-1\"",
                completed.xmlPath().getString("CompleteMultipartUploadResult.ETag"));

        assertXmlEtag("multipart.bin", "\"8d780cfbcd9622aa77011564e615065f-1\"");
        given().when().get("/storage/v1/b/{bucket}/o/multipart.bin", bucket)
                .then().statusCode(200)
                .body("etag", equalTo("8d780cfbcd9622aa77011564e615065f-1"))
                .body("md5Hash", nullValue());
    }

    @Test
    void multipartEtagHashesThePartDigestsInPartOrder() {
        String path = "/" + bucket + "/two-parts.bin";
        String uploadId = initiate(path);
        byte[] first = new byte[5 * 1024 * 1024];
        Arrays.fill(first, (byte) 123);
        String firstEtag = given().body(first).put(path + "?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200)
                .header("ETag", equalTo("\"387397a46e43a5a369e2bcd2cd27dcdf\""))
                .extract().header("ETag");
        String lastEtag = given().body("tail").put(path + "?uploadId=" + uploadId + "&partNumber=2")
                .then().statusCode(200)
                .header("ETag", equalTo("\"7aea2552dfe7eb84b9443b6fc9ba6e01\""))
                .extract().header("ETag");

        given().contentType("application/xml")
                .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + firstEtag
                        + "</ETag></Part><Part><PartNumber>2</PartNumber><ETag>" + lastEtag
                        + "</ETag></Part></CompleteMultipartUpload>")
                .post(path + "?uploadId=" + uploadId)
                .then().statusCode(200)
                .header("ETag", equalTo("\"f1532f7e30ab306902f68a0323248750-2\""));

        assertXmlEtag("two-parts.bin", "\"f1532f7e30ab306902f68a0323248750-2\"");
    }

    @Test
    void completionAcceptsAPartReceiptWithoutItsQuotes() {
        String path = "/" + bucket + "/unquoted.bin";
        String uploadId = initiate(path);
        given().body("hello world\n".getBytes(StandardCharsets.UTF_8))
                .put(path + "?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200)
                .header("ETag", equalTo(PLAIN_ETAG));

        given().contentType("application/xml")
                .body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                        + PLAIN_ETAG.replace("\"", "") + "</ETag></Part></CompleteMultipartUpload>")
                .post(path + "?uploadId=" + uploadId)
                .then().statusCode(200)
                .header("ETag", equalTo("\"8d780cfbcd9622aa77011564e615065f-1\""));

        assertXmlEtag("unquoted.bin", "\"8d780cfbcd9622aa77011564e615065f-1\"");
    }

    private String initiate(String path) {
        return given().post(path + "?uploads").then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private void putPlain(String name, String content) {
        given().contentType("text/plain").body(content.getBytes(StandardCharsets.UTF_8))
                .when().put("/{bucket}/{object}", bucket, name)
                .then().statusCode(200);
    }

    private void assertXmlEtag(String name, String expected) {
        Response get = given().when().get("/{bucket}/{object}", bucket, name);
        get.then().statusCode(200).header("ETag", equalTo(expected));
        assertEquals(1, get.headers().getValues("ETag").size());
        given().when().head("/{bucket}/{object}", bucket, name)
                .then().statusCode(200)
                .header("ETag", equalTo(expected));
        String listed = given().queryParam("prefix", name).when().get("/{bucket}", bucket)
                .then().statusCode(200)
                .extract().xmlPath().getString("ListBucketResult.Contents.ETag");
        assertEquals(expected, listed);
    }
}
