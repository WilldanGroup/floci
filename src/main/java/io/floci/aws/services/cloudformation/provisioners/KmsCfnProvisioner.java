package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.kms.model.KmsKey;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::KMS::Key}, {@code AWS::KMS::Alias} and {@code AWS::KMS::ReplicaKey}.
 *
 * <p>A key honours {@code MultiRegion} and {@code KeyPolicy}: a multi-Region key is a primary
 * whose key id starts {@code mrk-}, as KMS mints one, so an {@code AWS::KMS::ReplicaKey} can
 * replicate it. A replica key is made by {@code ReplicateKey} from the primary its
 * {@code PrimaryKeyArn} names, in that ARN's region, into the stack's region, with the key policy,
 * description and tags the template gives; {@code Ref} and {@code Fn::GetAtt KeyId} are the key id
 * it shares with its primary, and {@code Fn::GetAtt Arn} its own ARN in the stack's region, as the
 * CloudFormation Template Reference's page for the type has them. Its key policy, description,
 * {@code Enabled} and tags update in place; a new {@code PrimaryKeyArn} replicates a new key.
 */
@ApplicationScoped
public class KmsCfnProvisioner implements CfnResourceProvisioner {

    private static final String KEY = "AWS::KMS::Key";
    private static final String ALIAS = "AWS::KMS::Alias";
    private static final String REPLICA_KEY = "AWS::KMS::ReplicaKey";
    /** The primary a replica was made from, which tells an in-place update from a new replica. */
    private static final String PRIMARY_KEY_ARN_ATTR = "__FlociPrimaryKeyArn";

    private final KmsService kmsService;

    public KmsCfnProvisioner(KmsService kmsService) {
        this.kmsService = kmsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(KEY, ALIAS, REPLICA_KEY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case KEY -> provisionKey(r, props, ctx);
            case ALIAS -> provisionAlias(r, props, ctx);
            case REPLICA_KEY -> provisionReplicaKey(r, props, ctx);
            default -> throw new IllegalStateException(
                    "KmsCfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        String description = ctx.resolveOptional(props, "Description");
        String requestedSpec = ctx.resolveOptional(props, "KeySpec");
        String requestedUsage = ctx.resolveOptional(props, "KeyUsage");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        String policy = keyPolicy(props, ctx);
        boolean multiRegion = "true".equalsIgnoreCase(ctx.resolveOptional(props, "MultiRegion"));
        KmsKey key = kmsService.createKey(description, requestedUsage, requestedSpec, policy, tags, null, multiRegion,
                ctx.region());
        r.setPhysicalId(key.getKeyId());
        r.getAttributes().put("Arn", key.getArn());
        r.getAttributes().put("KeyId", key.getKeyId());
    }

    private void provisionAlias(StackResource r, JsonNode props, ProvisionContext ctx) {
        String aliasName = ctx.resolveOptional(props, "AliasName");
        String targetKeyId = ctx.resolveOptional(props, "TargetKeyId");
        if (aliasName != null && targetKeyId != null) {
            kmsService.createAlias(aliasName, targetKeyId, ctx.region());
        }
        r.setPhysicalId(aliasName != null
                ? aliasName
                : "alias/cfn-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void provisionReplicaKey(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String primaryArn = ctx.resolveOptional(props, "PrimaryKeyArn");
        if (primaryArn == null || !AwsArnUtils.isArn(primaryArn)) {
            throw new AwsException("ValidationError", "Property PrimaryKeyArn of " + r.getLogicalId()
                    + " must be the key ARN of a multi-Region primary key.", 400);
        }
        String policy = keyPolicy(props, ctx);
        String description = ctx.resolveOptional(props, "Description");
        String enabled = ctx.resolveOptional(props, "Enabled");
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, ctx);
        String region = ctx.region();

        KmsKey replica;
        if (ctx.isUpdate() && primaryArn.equals(attributesBefore.get(PRIMARY_KEY_ARN_ATTR))) {
            String keyId = ctx.priorPhysicalId();
            if (policy != null) {
                kmsService.putKeyPolicy(keyId, policy, region);
            }
            kmsService.updateKeyDescription(keyId, description == null ? "" : description, region);
            List<String> stale = ProvisionContext.staleTagKeys(kmsService.describeKey(keyId, region).getTags(), tags);
            if (!stale.isEmpty()) {
                kmsService.untagResource(keyId, stale, region);
            }
            if (!tags.isEmpty()) {
                kmsService.tagResource(keyId, tags, region);
            }
            replica = kmsService.describeKey(keyId, region);
        } else {
            replica = kmsService.replicateKey(primaryArn, description, policy, tags, region,
                    AwsArnUtils.parse(primaryArn).region());
        }
        boolean wantEnabled = enabled == null || enabled.isBlank() || Boolean.parseBoolean(enabled);
        if (wantEnabled && !replica.isEnabled()) {
            kmsService.enableKey(replica.getKeyId(), region);
        } else if (!wantEnabled && replica.isEnabled()) {
            kmsService.disableKey(replica.getKeyId(), region);
        }

        r.setPhysicalId(replica.getKeyId());
        r.getAttributes().put("Arn", replica.getArn());
        r.getAttributes().put("KeyId", replica.getKeyId());
        r.getAttributes().put(PRIMARY_KEY_ARN_ATTR, primaryArn);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // A KMS key cannot be deleted immediately, only scheduled, so a stack delete leaves it, a
        // replica key as a key.
        if (ALIAS.equals(resourceType)) {
            kmsService.deleteAlias(physicalId, region);
        }
    }

    /** {@code KeyPolicy} as JSON text, or null when the template declares none and KMS's default applies. */
    private static String keyPolicy(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("KeyPolicy") || props.get("KeyPolicy").isNull()) {
            return null;
        }
        String policy = ctx.engine().resolveJsonAttributeStrict(props.get("KeyPolicy"));
        return policy == null || policy.isBlank() ? null : policy;
    }

    /**
     * Kept rather than delegating to {@link ProvisionContext#resolveTags}, which is not
     * equivalent: it skips a blank key this keeps and orders entries by insertion rather than hash.
     */
    private Map<String, String> parseCfnTags(JsonNode tagsNode, ProvisionContext ctx) {
        tagsNode = ctx.engine().resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = ctx.engine().resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
