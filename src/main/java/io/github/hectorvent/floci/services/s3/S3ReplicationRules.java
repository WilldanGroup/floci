package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bucket's replication rules, read from the {@code ReplicationConfiguration} document
 * PutBucketReplication stored, and the destinations a new object is replicated to.
 *
 * <p>As the Amazon S3 User Guide's replication configuration page has it: a rule applies when its
 * {@code Status} is {@code Enabled} and its filter matches the object, the filter being a
 * {@code Prefix} (in {@code Filter}, in {@code Filter/And}, or the earlier top-level form), a
 * {@code Tag}, or an {@code And} of a prefix and tags, all of which must match; no filter, or an
 * empty one, matches every object. Its {@code Destination/Bucket} is the bucket's ARN,
 * {@code arn:<partition>:s3:::<bucket>}. Where two rules name one destination bucket, the rule of
 * the higher {@code Priority} decides the replica's storage class, and the object is replicated
 * there once.
 */
public final class S3ReplicationRules {

    /** A bucket a matching rule replicates to, with the storage class the rule gives the replica. */
    public record Destination(String bucket, String storageClass) {
    }

    private record Rule(int priority, String prefix, Map<String, String> tags, Destination destination) {
        boolean matches(String key, Map<String, String> objectTags) {
            if (prefix != null && !key.startsWith(prefix)) {
                return false;
            }
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                if (objectTags == null || !tag.getValue().equals(objectTags.get(tag.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final S3ReplicationRules NONE = new S3ReplicationRules(List.of());

    private final List<Rule> rules;

    private S3ReplicationRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** The enabled rules of a stored configuration; none where the bucket has no configuration. */
    public static S3ReplicationRules of(String replicationXml) {
        XmlElement root = XmlParser.extractElementTree(replicationXml, "ReplicationConfiguration");
        if (root == null) {
            return NONE;
        }
        List<Rule> rules = new ArrayList<>();
        for (XmlElement rule : root.children()) {
            if (!"Rule".equals(rule.name()) || !"Enabled".equals(text(rule.child("Status")))) {
                continue;
            }
            XmlElement destination = rule.child("Destination");
            String bucketArn = destination == null ? null : text(destination.child("Bucket"));
            if (bucketArn == null || bucketArn.isBlank()) {
                continue;
            }
            String bucket = AwsArnUtils.isArn(bucketArn) ? AwsArnUtils.parse(bucketArn).resource() : bucketArn;
            Map<String, String> tags = new LinkedHashMap<>();
            String prefix = text(rule.child("Prefix"));
            XmlElement filter = rule.child("Filter");
            if (filter != null) {
                XmlElement scope = filter.child("And") != null ? filter.child("And") : filter;
                if (scope.child("Prefix") != null) {
                    prefix = text(scope.child("Prefix"));
                }
                for (XmlElement tag : scope.children()) {
                    if ("Tag".equals(tag.name())) {
                        tags.put(text(tag.child("Key")), text(tag.child("Value")));
                    }
                }
            }
            int priority = parsePriority(text(rule.child("Priority")));
            rules.add(new Rule(priority, prefix == null || prefix.isEmpty() ? null : prefix, tags,
                    new Destination(bucket, text(destination.child("StorageClass")))));
        }
        return new S3ReplicationRules(List.copyOf(rules));
    }

    /** Whether any enabled rule replicates an object of this key and these tags. */
    public boolean replicates(String key, Map<String, String> objectTags) {
        return rules.stream().anyMatch(rule -> rule.matches(key, objectTags));
    }

    /** The buckets an object of this key and these tags is replicated to, each once. */
    public List<Destination> destinations(String key, Map<String, String> objectTags) {
        Map<String, Rule> byBucket = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.matches(key, objectTags)) {
                byBucket.merge(rule.destination().bucket(), rule,
                        (kept, other) -> other.priority() > kept.priority() ? other : kept);
            }
        }
        return byBucket.values().stream().map(Rule::destination).toList();
    }

    private static int parsePriority(String priority) {
        try {
            return priority == null || priority.isBlank() ? 0 : Integer.parseInt(priority.trim());
        } catch (NumberFormatException ignored) {
            // A priority that is no number ranks lowest; S3 refuses such a document when it is put.
            return 0;
        }
    }

    private static String text(XmlElement element) {
        return element == null ? null : element.text();
    }
}
