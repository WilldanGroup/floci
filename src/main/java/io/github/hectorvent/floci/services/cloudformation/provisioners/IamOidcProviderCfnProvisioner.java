package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::OIDCProvider}. {@code Ref} and {@code Fn::GetAtt
 * Arn} both give the provider's ARN, which is derived from its URL.
 *
 * <p>{@code Url} is createOnly, so an update that changes it replaces the provider: the new one is
 * created and the displaced one is deleted once the update commits (or restored on rollback), via
 * {@link ReplacementCleanup}. Client IDs, thumbprints and tags change in place.
 */
@ApplicationScoped
public class IamOidcProviderCfnProvisioner implements CfnResourceProvisioner {

    private final IamService iamService;

    @Inject
    public IamOidcProviderCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::IAM::OIDCProvider");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String url = ctx.resolveOptional(props, "Url");
        List<String> clientIds = ctx.resolveStringList(props, "ClientIdList");
        List<String> thumbprints = ctx.resolveStringList(props, "ThumbprintList");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        OpenIDConnectProvider prior = ctx.isUpdate() ? findProvider(ctx.priorPhysicalId()) : null;
        if (prior != null && ("https://" + prior.getUrl()).equals(url)) {
            reconcile(prior, clientIds, thumbprints, tags);
        } else {
            OpenIDConnectProvider created = iamService.createOpenIDConnectProvider(url, clientIds, thumbprints, tags);
            r.setPhysicalId(created.getArn());
            r.getAttributes().put("Arn", created.getArn());
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** The prior provider, or null when it was deleted out of band so the update recreates it. */
    private OpenIDConnectProvider findProvider(String arn) {
        try {
            return iamService.getOpenIDConnectProvider(arn);
        } catch (AwsException e) {
            if ("NoSuchEntity".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private void reconcile(OpenIDConnectProvider provider, List<String> clientIds, List<String> thumbprints,
                           Map<String, String> tags) {
        String arn = provider.getArn();
        for (String clientId : clientIds) {
            iamService.addClientIdToOpenIDConnectProvider(arn, clientId);
        }
        for (String clientId : new ArrayList<>(provider.getClientIdList())) {
            if (!clientIds.contains(clientId)) {
                iamService.removeClientIdFromOpenIDConnectProvider(arn, clientId);
            }
        }
        if (!thumbprints.equals(provider.getThumbprintList())) {
            iamService.updateOpenIDConnectProviderThumbprint(arn, thumbprints);
        }
        List<String> droppedTags = new ArrayList<>(provider.getTags().keySet());
        droppedTags.removeAll(tags.keySet());
        if (!droppedTags.isEmpty()) {
            iamService.untagOpenIDConnectProvider(arn, droppedTags);
        }
        if (!tags.isEmpty()) {
            iamService.tagOpenIDConnectProvider(arn, tags);
        }
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

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("OIDC provider", physicalId,
                () -> iamService.deleteOpenIDConnectProvider(physicalId), "NoSuchEntity");
    }
}
