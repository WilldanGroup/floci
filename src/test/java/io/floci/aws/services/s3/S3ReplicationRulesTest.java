package io.floci.aws.services.s3;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which objects a stored replication configuration replicates, and where to. */
class S3ReplicationRulesTest {

    private static String configuration(String... rules) {
        return "<ReplicationConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Role>arn:aws:iam::000000000000:role/r</Role>" + String.join("", rules)
                + "</ReplicationConfiguration>";
    }

    private static String rule(String status, String priority, String filter, String bucket, String storageClass) {
        return "<Rule><Status>" + status + "</Status>"
                + (priority == null ? "" : "<Priority>" + priority + "</Priority>")
                + (filter == null ? "" : filter)
                + "<Destination><Bucket>arn:aws:s3:::" + bucket + "</Bucket>"
                + (storageClass == null ? "" : "<StorageClass>" + storageClass + "</StorageClass>")
                + "</Destination></Rule>";
    }

    @Test
    void aPrefixFilterReplicatesOnlyKeysUnderIt() {
        S3ReplicationRules rules = S3ReplicationRules.of(configuration(
                rule("Enabled", "1", "<Filter><Prefix>templates/</Prefix></Filter>", "west", null)));

        assertTrue(rules.replicates("templates/a.json", Map.of()));
        assertFalse(rules.replicates("staging/a.json", Map.of()));
        assertEquals(List.of(new S3ReplicationRules.Destination("west", null)),
                rules.destinations("templates/a.json", Map.of()));
    }

    @Test
    void anAndFilterNeedsItsPrefixAndEveryTag() {
        S3ReplicationRules rules = S3ReplicationRules.of(configuration(rule("Enabled", "1",
                "<Filter><And><Prefix>logs/</Prefix><Tag><Key>keep</Key><Value>yes</Value></Tag></And></Filter>",
                "west", null)));

        assertTrue(rules.replicates("logs/a", Map.of("keep", "yes")));
        assertFalse(rules.replicates("logs/a", Map.of("keep", "no")));
        assertFalse(rules.replicates("logs/a", Map.of()));
        assertFalse(rules.replicates("other/a", Map.of("keep", "yes")));
    }

    @Test
    void aRuleWithNoFilterOrTheEarlierPrefixFormMatchesAsWritten() {
        assertTrue(S3ReplicationRules.of(configuration(rule("Enabled", null, null, "west", null)))
                .replicates("anything", null));
        S3ReplicationRules legacy = S3ReplicationRules.of(configuration(
                rule("Enabled", null, "<Prefix>docs/</Prefix>", "west", null)));
        assertTrue(legacy.replicates("docs/a", null));
        assertFalse(legacy.replicates("img/a", null));
    }

    @Test
    void aDisabledRuleReplicatesNothing() {
        assertFalse(S3ReplicationRules.of(configuration(rule("Disabled", "1", null, "west", null)))
                .replicates("templates/a.json", Map.of()));
        assertFalse(S3ReplicationRules.of(null).replicates("templates/a.json", Map.of()));
    }

    @Test
    void eachDestinationIsTakenOnceAtItsHighestPriorityRule() {
        S3ReplicationRules rules = S3ReplicationRules.of(configuration(
                rule("Enabled", "1", null, "west", "STANDARD"),
                rule("Enabled", "5", "<Filter><Prefix>a/</Prefix></Filter>", "west", "GLACIER"),
                rule("Enabled", "2", null, "east", null)));

        assertEquals(List.of(new S3ReplicationRules.Destination("west", "GLACIER"),
                        new S3ReplicationRules.Destination("east", null)),
                rules.destinations("a/key", Map.of()));
        assertEquals(List.of(new S3ReplicationRules.Destination("west", "STANDARD"),
                        new S3ReplicationRules.Destination("east", null)),
                rules.destinations("b/key", Map.of()));
    }
}
