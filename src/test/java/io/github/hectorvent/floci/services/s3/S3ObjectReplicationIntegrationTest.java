package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ReplicationStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * S3 Replication as the class registry uses it: a versioned bucket replicating everything under a
 * prefix to a versioned bucket in another region, and that bucket replicating back. A written
 * object reads {@code PENDING} and then {@code COMPLETED} on its source, and its copy, the version's
 * bytes, content type and metadata, reads {@code REPLICA} in the other region and is replicated no
 * further; an object outside the rule's prefix and a delete marker are not replicated; a source
 * whose destination cannot take the copy reads {@code FAILED} (Amazon S3 User Guide, Getting
 * replication status information).
 */
@QuarkusTest
class S3ObjectReplicationIntegrationTest {

    private static final String ACCOUNT = "741624100071";
    private static final String HOME = "object-replication-it-home";
    private static final String WEST = "object-replication-it-west";
    private static final String ORPHAN = "object-replication-it-orphan";

    private S3Client east;
    private S3Client west;

    @BeforeEach
    void openClients() {
        east = client(Region.US_EAST_1);
        west = client(Region.US_WEST_2);
    }

    @AfterEach
    void closeClients() {
        east.close();
        west.close();
    }

    private static S3Client client(Region region) {
        return S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .forcePathStyle(true)
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build();
    }

    private static String replicationTo(String destination) {
        return """
                <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Role>arn:aws:iam::%s:role/registry-replication</Role>
                  <Rule>
                    <ID>templates</ID>
                    <Priority>1</Priority>
                    <Status>Enabled</Status>
                    <Filter><Prefix>templates/</Prefix></Filter>
                    <DeleteMarkerReplication><Status>Disabled</Status></DeleteMarkerReplication>
                    <Destination><Bucket>arn:aws:s3:::%s</Bucket></Destination>
                  </Rule>
                </ReplicationConfiguration>
                """.formatted(ACCOUNT, destination);
    }

    @Test
    void anObjectWrittenUnderTheRulesPrefixIsCopiedToTheOtherRegionsBucketAndNoFurther() {
        east.createBucket(b -> b.bucket(HOME));
        west.createBucket(b -> b.bucket(WEST).createBucketConfiguration(c -> c
                .locationConstraint(BucketLocationConstraint.US_WEST_2)));
        east.putBucketVersioning(b -> b.bucket(HOME).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        west.putBucketVersioning(b -> b.bucket(WEST).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        putReplication(HOME, replicationTo(WEST));
        putReplication(WEST, replicationTo(HOME));

        byte[] parent = "{\"Resources\":{}}".getBytes(StandardCharsets.UTF_8);
        east.putObject(p -> p.bucket(HOME).key("staging/build-1/out.zip"), RequestBody.fromString("staged"));
        String versionId = east.putObject(p -> p.bucket(HOME).key("templates/switchboard/1.0.0/switchboard.json")
                        .contentType("application/json").metadata(Map.of("stamp", "one")),
                RequestBody.fromBytes(parent)).versionId();

        ReplicationStatus first = east.headObject(h -> h.bucket(HOME).key("templates/switchboard/1.0.0/switchboard.json"))
                .replicationStatus();
        assertTrue(first == ReplicationStatus.PENDING || first == ReplicationStatus.COMPLETED, String.valueOf(first));
        awaitStatus(HOME, "templates/switchboard/1.0.0/switchboard.json", ReplicationStatus.COMPLETED);

        HeadObjectResponse replica = west.headObject(h -> h.bucket(WEST).key("templates/switchboard/1.0.0/switchboard.json"));
        assertEquals(ReplicationStatus.REPLICA, replica.replicationStatus());
        assertEquals("application/json", replica.contentType());
        assertEquals("one", replica.metadata().get("stamp"));
        assertNotNull(replica.versionId());
        assertArrayEquals(parent, west.getObjectAsBytes(g -> g.bucket(WEST)
                .key("templates/switchboard/1.0.0/switchboard.json")).asByteArray());
        // The replica's own bucket replicates back, and a replica is replicated no further.
        assertEquals(1, east.listObjectVersions(l -> l.bucket(HOME).prefix("templates/")).versions().size());
        assertEquals(versionId, east.headObject(h -> h.bucket(HOME).key("templates/switchboard/1.0.0/switchboard.json"))
                .versionId());

        // Outside the rule's prefix: no status, and no copy.
        assertNull(east.headObject(h -> h.bucket(HOME).key("staging/build-1/out.zip")).replicationStatus());
        assertTrue(west.listObjectsV2(l -> l.bucket(WEST).prefix("staging/")).contents().isEmpty());

        // A delete marker is not replicated: the copy stands once a later write has replicated.
        east.deleteObject(d -> d.bucket(HOME).key("templates/switchboard/1.0.0/switchboard.json"));
        east.putObject(p -> p.bucket(HOME).key("templates/switchboard/1.0.1/switchboard.json"),
                RequestBody.fromString("{}"));
        awaitStatus(HOME, "templates/switchboard/1.0.1/switchboard.json", ReplicationStatus.COMPLETED);
        assertEquals(ReplicationStatus.REPLICA, west.headObject(h -> h.bucket(WEST)
                .key("templates/switchboard/1.0.0/switchboard.json")).replicationStatus());
    }

    @Test
    void aSourceWhoseDestinationCannotTakeTheCopyReadsFailed() {
        east.createBucket(b -> b.bucket(ORPHAN));
        putReplication(ORPHAN, replicationTo("object-replication-it-no-such-bucket"));

        east.putObject(p -> p.bucket(ORPHAN).key("templates/lost.json"), RequestBody.fromString("{}"));

        awaitStatus(ORPHAN, "templates/lost.json", ReplicationStatus.FAILED);
    }

    private void putReplication(String bucket, String document) {
        RestAssured.given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261110/us-east-1/s3/aws4_request")
                .body(document)
                .when().put("/" + bucket + "?replication")
                .then().statusCode(200);
    }

    private void awaitStatus(String bucket, String key, ReplicationStatus expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        ReplicationStatus last = null;
        while (System.currentTimeMillis() < deadline) {
            last = east.headObject(h -> h.bucket(bucket).key(key)).replicationStatus();
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + bucket + "/" + key, e);
            }
        }
        fail(bucket + "/" + key + " did not read " + expected + " within ten seconds, last " + last);
    }
}
