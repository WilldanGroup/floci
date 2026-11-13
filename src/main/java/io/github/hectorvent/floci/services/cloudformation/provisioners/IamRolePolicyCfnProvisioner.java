package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::RolePolicy}: one inline policy on one role, put with
 * {@code PutRolePolicy} and removed with {@code DeleteRolePolicy}, as distinct from {@code AWS::IAM::Policy},
 * which embeds one document in several principals. {@code Ref} is the registry's primary identifier,
 * {@code <PolicyName>|<RoleName>}, and the type exposes no attribute. {@code PolicyName} and {@code RoleName}
 * are create-only: an update changing either puts the policy under the new name or on the new role and
 * deletes the old one once the stack has finished updating, as a replacement, while a changed
 * {@code PolicyDocument} is put in place.
 */
@ApplicationScoped
public class IamRolePolicyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::RolePolicy";

    private final IamService iamService;

    @Inject
    public IamRolePolicyCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String policyName = required(ctx.resolveOptional(props, "PolicyName"), "PolicyName", r);
        String roleName = required(ctx.resolveOptional(props, "RoleName"), "RoleName", r);
        if (props == null || !props.hasNonNull("PolicyDocument")) {
            // The registry schema leaves PolicyDocument optional, but PutRolePolicy, which the resource is, needs one.
            throw missing("PolicyDocument", r);
        }
        iamService.putRolePolicy(roleName, policyName, ctx.resolvePolicyDocument(props));
        r.setPhysicalId(policyName + "|" + roleName);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** Removes the policy from its role; one already gone, or whose role is gone, deletes silently. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        int separator = physicalId == null ? -1 : physicalId.indexOf('|');
        if (separator < 0) {
            return;
        }
        String policyName = physicalId.substring(0, separator);
        String roleName = physicalId.substring(separator + 1);
        CfnDeletes.safeDelete("IAM role policy", physicalId,
                () -> iamService.deleteRolePolicy(roleName, policyName), "NoSuchEntity");
    }

    /** The id joins the policy's own name and its role's; its prefix, the policy name, names no parent. */
    @Override
    public boolean idPrefixCanNameParent(String resourceType) {
        return false;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    private static String required(String value, String property, StackResource r) {
        if (value == null || value.isBlank()) {
            throw missing(property, r);
        }
        return value;
    }

    private static AwsException missing(String property, StackResource r) {
        return new AwsException("ValidationError",
                TYPE + " " + r.getLogicalId() + " is missing the required " + property + " property", 400);
    }
}
