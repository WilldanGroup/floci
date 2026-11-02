package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::CloudFormation::WaitConditionHandle}, which has no properties and backs no service. Its
 * {@code Ref} is a URL naming the stack and the handle, and {@code Fn::GetAtt Id} the same, as the CloudFormation
 * Template Reference's page has a reference return a URL to signal to; Floci serves no
 * {@code AWS::CloudFormation::WaitCondition}, so nothing reads a signal sent to it. Updates aren't supported for the
 * type, so an update keeps the handle it stands on.
 */
@ApplicationScoped
public class WaitConditionHandleCfnProvisioner implements CfnResourceProvisioner {

    private final EmulatorConfig config;

    @Inject
    public WaitConditionHandleCfnProvisioner(EmulatorConfig config) {
        this.config = config;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CloudFormation::WaitConditionHandle");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String url = ctx.isUpdate()
                ? ctx.priorPhysicalId()
                : config.effectiveBaseUrl() + "/cloudformation-waitcondition/" + ctx.region() + "/"
                        + ctx.accountId() + "/" + URLEncoder.encode(ctx.stackName(), StandardCharsets.UTF_8) + "/"
                        + URLEncoder.encode(r.getLogicalId(), StandardCharsets.UTF_8) + "/" + UUID.randomUUID();
        r.setPhysicalId(url);
        r.getAttributes().put("Id", url);
    }
}
