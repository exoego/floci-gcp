package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.RequestBaseUrl;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectDownload;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.iam.GcsIamAuthorizationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Handles GCS XML API requests: GET on a bucket (list objects) and GET/PUT/DELETE on an object.
 * Used by the Go SDK (STORAGE_EMULATOR_HOST), for signed-URL access, and by S3-shaped tooling
 * that predates the JSON API.
 */
@ApplicationScoped
@Path("/{bucket: [a-z0-9._-]+}")
public class GcsXmlDownloadController {

    private final GcsXmlAuthorization xmlAuthorization;
    private final GcsService service;
    private final EmulatorConfig config;
    private final GcsXmlMultipartHandler multipart;
    private final GcsIamAuthorizationService iamAuthorizationService;

    @Inject
    public GcsXmlDownloadController(GcsService service, EmulatorConfig config,
            GcsXmlMultipartHandler multipart, GcsIamAuthorizationService iamAuthorizationService,
            GcsXmlAuthorization xmlAuthorization) {
        this.xmlAuthorization = xmlAuthorization;
        this.multipart = multipart;
        this.service = service;
        this.config = config;
        this.iamAuthorizationService = iamAuthorizationService;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_XML)
    @Path("/{object: .+}")
    public Response multipart(@PathParam("bucket") String bucket, @PathParam("object") String object,
            @Context UriInfo uri, @Context HttpHeaders headers, byte[] body) {
        return multipart.handle("POST", bucket, object, uri, headers, body);
    }

    @OPTIONS
    @Path("/{object: .*}")
    public Response options() {
        return Response.ok().build();
    }

    @OPTIONS
    public Response optionsBucket() {
        return Response.ok().build();
    }

    @GET
    @Path("/{object: .+}")
    public Response download(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @Context HttpHeaders headers,
            @QueryParam("generation") String generation,
            @HeaderParam("x-goog-encryption-key-sha256") String customerEncryptionKeySha256,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Range") String rangeHeader,
            @HeaderParam("Accept-Encoding") String acceptEncoding) {
        if (GcsXmlMultipartHandler.matches(uriInfo)) { return multipart.handle("GET", bucket, objectPath, uriInfo, headers, null); }
        GcsSignedUrl.checkNotExpired(uriInfo);
        xmlAuthorization.require(authorization, () -> iamAuthorizationService.requireObjectRead(authorization, bucket, objectPath));
        GcsCustomerEncryption customerEncryption = GcsCustomerEncryption.fromKeySha256(customerEncryptionKeySha256);
        GcsObjectDownload download = service.getObjectForDownload(bucket, objectPath, generation, customerEncryption);
        return GcsMediaResponses.withResponseOverrides(
                GcsMediaResponses.xmlMediaResponse(download.data(), download.meta(), rangeHeader, acceptEncoding),
                uriInfo.getQueryParameters());
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    @Path("/{object: .+}")
    public Response upload(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @Context HttpHeaders headers,
            byte[] body) {
        if (GcsXmlMultipartHandler.matches(uriInfo)) { return multipart.handle("PUT", bucket, objectPath, uriInfo, headers, body); }
        GcsSignedUrl.checkNotExpired(uriInfo);
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String baseUrl = RequestBaseUrl.resolve(uriInfo, headers, config.baseUrl(), config.port());
        Function<GcsIamAuthorizationService.OverwriteAuthorization, Response> upload = overwriteAuthorization -> {
            GcsObjectMeta meta = service.putObject(
                    bucket, objectPath, contentType, body != null ? body : new byte[0],
                    GcsCustomerEncryption.fromHeaders(headers), googMetaHeaders(headers), null,
                    GcsObjectPreconditions.NONE, baseUrl, overwriteAuthorization::require);
            return GcsMediaResponses.uploadResponse(meta);
        };
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        return xmlAuthorization.call(authorization, () -> iamAuthorizationService.authorizeObjectCreate(
                authorization, bucket, objectPath, upload));
    }

    /**
     * XML API DELETE. The JSON API equivalent is {@code DELETE /storage/v1/b/{b}/o/{o}}; a client
     * speaking the XML API expects a bare 204 here rather than a 405.
     */
    @DELETE
    @Path("/{object: .+}")
    public Response delete(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @Context HttpHeaders headers,
            @QueryParam("generation") String generation,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        if (GcsXmlMultipartHandler.matches(uriInfo)) { return multipart.handle("DELETE", bucket, objectPath, uriInfo, headers, null); }
        GcsSignedUrl.checkNotExpired(uriInfo);
        xmlAuthorization.require(authorization, () -> iamAuthorizationService.requireObjectDelete(authorization, bucket, objectPath));
        service.deleteObjectOrThrow(bucket, objectPath, generation);
        return Response.noContent().build();
    }

    /**
     * XML API ListObjects: {@code GET /{bucket}}, answering with {@code ListBucketResult} in
     * the list-type 1 shape, or the list-type 2 shape when {@code list-type=2} is sent.
     */
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response listObjects(
            @PathParam("bucket") String bucket,
            @Context UriInfo uriInfo,
            @Context HttpHeaders headers,
            @QueryParam("prefix") String prefix,
            @QueryParam("delimiter") String delimiter,
            @QueryParam("max-keys") String maxKeys,
            @QueryParam("marker") String marker,
            @QueryParam("list-type") String listType,
            @QueryParam("start-after") String startAfter,
            @QueryParam("continuation-token") String continuationToken,
            @QueryParam("encoding-type") String encodingType,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        if (GcsXmlMultipartHandler.matches(uriInfo)) { return multipart.handle("GET", bucket, null, uriInfo, headers, null); }
        GcsSignedUrl.checkNotExpired(uriInfo);
        xmlAuthorization.require(authorization, () -> iamAuthorizationService.requireObjectList(authorization, bucket, prefix));
        GcsBucket stored = service.getBucket(bucket);
        return GcsXmlObjectListing.respond(stored, service.listObjects(bucket), new GcsXmlObjectListing.Query(
                prefix, delimiter, maxKeys, marker, listType, startAfter, continuationToken, encodingType));
    }

    private static void appendTag(StringBuilder xml, String tag, String value) {
        if (value == null) {
            return;
        }
        xml.append('<').append(tag).append('>').append(escapeXml(value)).append("</").append(tag).append('>');
    }

    @ServerExceptionMapper
    Response mapDomainFailure(GcpException error) {
        return GcsXmlErrorResponse.from(error);
    }

    @ServerExceptionMapper
    Response mapUnexpectedFailure(Exception error) {
        return GcsXmlErrorResponse.from(error);
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static Map<String, String> googMetaHeaders(HttpHeaders headers) {
        String prefix = GcsMediaResponses.META_HEADER_PREFIX;
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String headerName : headers.getRequestHeaders().keySet()) {
            String lower = headerName.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                metadata.put(lower.substring(prefix.length()), headers.getHeaderString(headerName));
            }
        }
        return metadata;
    }
}
