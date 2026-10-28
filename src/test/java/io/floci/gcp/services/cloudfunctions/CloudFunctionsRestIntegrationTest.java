package io.floci.gcp.services.cloudfunctions;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudFunctionsRestIntegrationTest {

    @Test
    void cloudFunctionsRestPathsUseProtoJsonUploadUrlAndLroPolling() {
        String project = "functions-it-1";
        String location = "us-central1";
        String functionId = "fn";
        String functionPath = "/v2/projects/" + project + "/locations/" + location + "/functions/" + functionId;

        String operationName = given()
                .contentType("application/json")
                .queryParam("functionId", functionId)
                .body("{\"buildConfig\":{\"runtime\":\"java21\"}}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/functions")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.functions.v2.Function"))
                .body("response.name", equalTo("projects/" + project + "/locations/" + location + "/functions/" + functionId))
                .body("response.state", equalTo("ACTIVE"))
                .body("response.environment", equalTo("GEN_2"))
                .body("response.serviceConfig.service",
                        equalTo("projects/" + project + "/locations/" + location + "/services/" + functionId))
                .extract().path("name");

        given()
                .when().get(functionPath)
                .then()
                .statusCode(200)
                .body("url", equalTo("https://" + location + "-" + project + ".cloudfunctions.net/" + functionId))
                .body("serviceConfig.allTrafficOnLatestRevision", equalTo(true));

        given()
                .queryParam("$alt", "json;enum-encoding=int")
                .when().get("/v2/projects/" + project + "/locations/" + location + "/functions")
                .then()
                .statusCode(200)
                .body("functions.name", hasItem("projects/" + project + "/locations/" + location + "/functions/" + functionId));

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(200)
                .body("metadata.operationType", equalTo("CREATE_FUNCTION"))
                .body("response.name", equalTo("projects/" + project + "/locations/" + location + "/functions/" + functionId));

        Response uploadTarget = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/functions:generateUploadUrl")
                .then()
                .statusCode(200)
                .body("uploadUrl", startsWith("http://"))
                .body("storageSource.bucket", startsWith("gcf-v2-sources-functions-it-1-us-central1"))
                .body("storageSource.object", startsWith("source-"))
                .extract().response();
        String uploadUrl = uploadTarget.path("uploadUrl");
        String sourceBucket = uploadTarget.path("storageSource.bucket");
        String sourceObject = uploadTarget.path("storageSource.object");

        given()
                .contentType("application/octet-stream")
                .body("fake zip bytes".getBytes(StandardCharsets.UTF_8))
                .when().put(URI.create(uploadUrl).getRawPath())
                .then()
                .statusCode(200)
                .header("ETag", notNullValue())
                .body(emptyString());

        given()
                .when().get("/storage/v1/b/" + sourceBucket + "/o/" + sourceObject)
                .then()
                .statusCode(200)
                .body("bucket", equalTo(sourceBucket))
                .body("name", equalTo(sourceObject))
                .body("size", equalTo("14"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + operationName + ":wait")
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        String deleteOperation = given()
                .when().delete(functionPath)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("name");

        given()
                .when().get("/v2/" + deleteOperation)
                .then()
                .statusCode(200)
                .body("metadata.operationType", equalTo("DELETE_FUNCTION"));

        given()
                .when().get(functionPath)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void validateOnlyCreateDoesNotPersistFunction() {
        String project = "functions-it-validate";
        String location = "us-central1";

        String operationName = given()
                .contentType("application/json")
                .queryParam("functionId", "fn")
                .queryParam("validateOnly", true)
                .body("{}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/functions")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("name");

        given()
                .when().get("/v2/projects/" + project + "/locations/" + location + "/functions/fn")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .when().get("/v2/projects/" + project + "/locations/" + location + "/operations")
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());
    }

    @Test
    void generateUploadUrlHonorsForwardedSchemeAndHost() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "functions.example.test")
                .body("{}")
                .when().post("/v2/projects/functions-it-forwarded/locations/us-central1/functions:generateUploadUrl")
                .then()
                .statusCode(200)
                .body("uploadUrl", startsWith("https://functions.example.test/"))
                .body("storageSource.sourceUploadUrl", startsWith("https://functions.example.test/"));
    }

    @Test
    void listFunctionsWithLocationWildcardSpansAllLocations() {
        String project = "functions-it-wildcard";
        for (String location : new String[]{"us-central1", "europe-west1"}) {
            given()
                    .contentType("application/json")
                    .queryParam("functionId", "fn")
                    .body("{\"buildConfig\":{\"runtime\":\"java21\"}}")
                    .when().post("/v2/projects/" + project + "/locations/" + location + "/functions")
                    .then()
                    .statusCode(200);
        }

        given()
                .when().get("/v2/projects/" + project + "/locations/-/functions")
                .then()
                .statusCode(200)
                .body("functions.name", containsInAnyOrder(
                        "projects/" + project + "/locations/europe-west1/functions/fn",
                        "projects/" + project + "/locations/us-central1/functions/fn"));
    }

    @Test
    void patchUpdatesMaskedFieldsThroughACompletedOperation() {
        String base = "/v2/projects/functions-it-patch/locations/us-central1/functions";
        given()
                .contentType("application/json")
                .queryParam("functionId", "fn")
                .body("{\"description\":\"before\",\"buildConfig\":{\"runtime\":\"java21\",\"entryPoint\":\"Example\"}}")
                .when().post(base)
                .then()
                .statusCode(200);

        String operationName = given()
                .contentType("application/json")
                .queryParam("updateMask", "description,buildConfig.runtime")
                .body("{\"description\":\"after\",\"buildConfig\":{\"runtime\":\"java25\"}}")
                .when().patch(base + "/fn")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.operationType", equalTo("UPDATE_FUNCTION"))
                .body("response.description", equalTo("after"))
                .extract().path("name");

        given()
                .when().get(base + "/fn")
                .then()
                .statusCode(200)
                .body("description", equalTo("after"))
                .body("buildConfig.runtime", equalTo("java25"))
                .body("buildConfig.entryPoint", equalTo("Example"))
                .body("state", equalTo("ACTIVE"))
                .body("serviceConfig.revision", endsWith("/revisions/fn-00002"));

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(200)
                .body("metadata.verb", equalTo("update"));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "description")
                .body("{}")
                .when().patch(base + "/missing")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void iamPolicyRoundTripsAndDiesWithTheFunction() {
        String base = "/v2/projects/functions-it-iam/locations/us-central1/functions";
        String create = "{\"buildConfig\":{\"runtime\":\"java21\"}}";
        given().contentType("application/json").queryParam("functionId", "fn").body(create)
                .when().post(base).then().statusCode(200);

        String emptyEtag = given()
                .urlEncodingEnabled(false)
                .when().get(base + "/fn:getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings", nullValue())
                .extract().path("etag");

        String etag = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/cloudfunctions.invoker\",\"members\":[\"allUsers\"]}]}}")
                .when().post(base + "/fn:setIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].role", equalTo("roles/cloudfunctions.invoker"))
                .body("bindings[0].members", contains("allUsers"))
                .extract().path("etag");

        given()
                .urlEncodingEnabled(false)
                .when().get(base + "/fn:getIamPolicy")
                .then()
                .statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].members", contains("allUsers"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"policy\":{\"etag\":\"" + emptyEtag + "\",\"bindings\":[]}}")
                .when().post(base + "/fn:setIamPolicy")
                .then()
                .statusCode(409)
                .body("error.status", equalTo("ABORTED"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"permissions\":[\"cloudfunctions.functions.invoke\"]}")
                .when().post(base + "/fn:testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", contains("cloudfunctions.functions.invoke"));

        given().when().delete(base + "/fn").then().statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .when().get(base + "/fn:getIamPolicy")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"permissions\":[\"cloudfunctions.functions.invoke\"]}")
                .when().post(base + "/fn:testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", nullValue());

        given().contentType("application/json").queryParam("functionId", "fn").body(create)
                .when().post(base).then().statusCode(200);
        given()
                .urlEncodingEnabled(false)
                .when().get(base + "/fn:getIamPolicy")
                .then()
                .statusCode(200)
                .body("etag", equalTo(emptyEtag))
                .body("bindings", nullValue());
    }
}
