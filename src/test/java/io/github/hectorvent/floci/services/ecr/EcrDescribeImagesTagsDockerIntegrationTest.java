package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.vertx.core.json.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code DescribeImages} answers one {@code ImageDetail} per image, an image being one manifest
 * digest, and that entry's {@code imageTags} are every tag of the repository that points at the
 * digest, whether the repository is listed or the image is asked for by a tag or by its digest.
 * The Amazon ECR API Reference defines {@code ImageDetail.imageDigest} as "The sha256 digest of
 * the image manifest" and {@code imageTags} as "The list of tags associated with this image", and
 * a reading on an AWS account (one manifest put under two tags, then {@code describe-images} with
 * no ids, by one tag and by the digest) answered one entry carrying both tags each time.
 *
 * <p>Tag order is not asserted: AWS answered the newest tag first, and Floci answers the backing
 * registry's order.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcrDescribeImagesTagsDockerIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

    private final String repo = "describe-tags/" + UUID.randomUUID().toString().substring(0, 8);
    private final byte[] config = "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    private final String configDigest = "sha256:" + Digests.sha256Hex(config);
    private String first;
    private String firstDigest;
    private String second;
    private String secondDigest;
    private Object firstPushedAt;

    private boolean created;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDockerAndImage("floci.services.ecr.registry-image");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Once, but per test: RestAssured has its port only once a test starts. */
    @BeforeEach
    void setUp() {
        if (created) {
            return;
        }
        created = true;
        call("CreateRepository", "{\"repositoryName\":\"" + repo + "\"}").statusCode(200);
        String upload = call("InitiateLayerUpload", "{\"repositoryName\":\"" + repo + "\"}")
                .statusCode(200).extract().path("uploadId");
        call("UploadLayerPart", "{\"repositoryName\":\"" + repo + "\",\"uploadId\":\"" + upload
                + "\",\"partFirstByte\":0,\"partLastByte\":" + (config.length - 1)
                + ",\"layerPartBlob\":\"" + Base64.getEncoder().encodeToString(config) + "\"}")
                .statusCode(200);
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + repo + "\",\"uploadId\":\"" + upload
                + "\",\"layerDigests\":[\"" + configDigest + "\"]}").statusCode(200);
        first = """
                {"schemaVersion":2,"mediaType":"%s",\
                "config":{"mediaType":"application/vnd.oci.image.config.v1+json","size":%d,"digest":"%s"},\
                "layers":[]}""".formatted(OCI_MANIFEST, config.length, configDigest);
        second = first.replace("\"layers\":[]", "\"layers\":[],\"annotations\":{\"build\":\"2\"}");
        firstDigest = "sha256:" + Digests.sha256Hex(first.getBytes(StandardCharsets.UTF_8));
        secondDigest = "sha256:" + Digests.sha256Hex(second.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Order(1)
    void theSameImageUnderASecondTagIsOneEntryWithBothTags() {
        call("PutImage", putImage(first, "a")).statusCode(200)
                .body("image.imageId.imageDigest", equalTo(firstDigest));
        firstPushedAt = describe("[{\"imageTag\":\"a\"}]").statusCode(200)
                .extract().path("imageDetails[0].imagePushedAt");

        call("PutImage", putImage(first, "b")).statusCode(200)
                .body("image.imageId.imageDigest", equalTo(firstDigest));

        describeAll()
                .statusCode(200)
                .body("imageDetails", hasSize(1))
                .body("imageDetails[0].imageDigest", equalTo(firstDigest))
                .body("imageDetails[0].imageTags", containsInAnyOrder("a", "b"))
                .body("imageDetails[0].imageManifestMediaType", equalTo(OCI_MANIFEST))
                .body("imageDetails[0].imagePushedAt", equalTo(firstPushedAt));
    }

    @Test
    @Order(2)
    void twoImagesAreTwoEntries() {
        call("PutImage", putImage(second, "c")).statusCode(200)
                .body("image.imageId.imageDigest", equalTo(secondDigest));

        describeAll()
                .statusCode(200)
                .body("imageDetails", hasSize(2))
                .body(tagsOf(firstDigest), containsInAnyOrder("a", "b"))
                .body(tagsOf(secondDigest), containsInAnyOrder("c"));
    }

    @Test
    @Order(3)
    void anImageAskedForByOneTagAnswersAllItsTags() {
        describe("[{\"imageTag\":\"a\"}]")
                .statusCode(200)
                .body("imageDetails", hasSize(1))
                .body("imageDetails[0].imageDigest", equalTo(firstDigest))
                .body("imageDetails[0].imageTags", containsInAnyOrder("a", "b"))
                .body("failures", hasSize(0));
    }

    @Test
    @Order(4)
    void anImageAskedForByDigestAnswersItsTags() {
        describe("[{\"imageDigest\":\"" + firstDigest + "\"}]")
                .statusCode(200)
                .body("imageDetails", hasSize(1))
                .body("imageDetails[0].imageDigest", equalTo(firstDigest))
                .body("imageDetails[0].imageTags", containsInAnyOrder("a", "b"));
    }

    @Test
    @Order(5)
    void anIdNamingNothingStillFails() {
        describe("[{\"imageTag\":\"a\"},{\"imageTag\":\"nope\"}]")
                .statusCode(200)
                .body("imageDetails", hasSize(1))
                .body("imageDetails[0].imageTags", containsInAnyOrder("a", "b"))
                .body("failures", hasSize(1))
                .body("failures[0].imageId.imageTag", equalTo("nope"))
                .body("failures[0].failureCode", equalTo("ImageNotFound"));

        describe("[{\"imageTag\":\"nope\"},{\"imageDigest\":\"sha256:" + "3".repeat(64) + "\"}]")
                .statusCode(400)
                .body("__type", equalTo("ImageNotFoundException"));
    }

    @Test
    @Order(6)
    void aMovedTagLeavesTheImageItLeft() {
        call("PutImage", putImage(second, "b")).statusCode(200)
                .body("image.imageId.imageDigest", equalTo(secondDigest));

        describeAll()
                .statusCode(200)
                .body("imageDetails", hasSize(2))
                .body(tagsOf(firstDigest), containsInAnyOrder("a"))
                .body(tagsOf(secondDigest), containsInAnyOrder("b", "c"));
        describe("[{\"imageDigest\":\"" + secondDigest + "\"}]")
                .statusCode(200)
                .body("imageDetails[0].imageTags", containsInAnyOrder("b", "c"));
    }

    @Test
    @Order(7)
    void anImageNoTagPointsAtCarriesNoTags() {
        String untagged = first.replace("\"layers\":[]", "\"layers\":[],\"annotations\":{\"build\":\"3\"}");
        String untaggedDigest = "sha256:" + Digests.sha256Hex(untagged.getBytes(StandardCharsets.UTF_8));
        call("PutImage", "{\"repositoryName\":\"" + repo + "\",\"imageDigest\":\"" + untaggedDigest
                + "\",\"imageManifest\":" + Json.encode(untagged) + "}").statusCode(200);

        describe("[{\"imageDigest\":\"" + untaggedDigest + "\"}]")
                .statusCode(200)
                .body("imageDetails", hasSize(1))
                .body("imageDetails[0].imageDigest", equalTo(untaggedDigest))
                .body("imageDetails[0].imageTags", nullValue());
    }

    /** Last, since every class on this profile shares one registry. */
    @Test
    @Order(8)
    void cleanUp() {
        call("DeleteRepository", "{\"repositoryName\":\"" + repo + "\",\"force\":true}").statusCode(200);
    }

    private static String tagsOf(String digest) {
        return "imageDetails.find { it.imageDigest == '" + digest + "' }.imageTags";
    }

    private ValidatableResponse describeAll() {
        return call("DescribeImages", "{\"repositoryName\":\"" + repo + "\"}");
    }

    private ValidatableResponse describe(String imageIds) {
        return call("DescribeImages", "{\"repositoryName\":\"" + repo + "\",\"imageIds\":" + imageIds + "}");
    }

    private String putImage(String imageManifest, String tag) {
        return "{\"repositoryName\":\"" + repo + "\",\"imageTag\":\"" + tag + "\",\"imageManifest\":"
                + Json.encode(imageManifest) + "}";
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .header("X-Amz-Target", PREFIX + action)
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
