package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::RolePolicy} in isolation: it puts the document on the one role, Ref is the registry's
 * {@code <PolicyName>|<RoleName>} with no attribute, a changed document is put in place, a changed name or
 * role is a replacement whose old policy the committed update deletes, and delete tolerates a policy already
 * gone.
 */
class IamRolePolicyCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::RolePolicy";

    private final IamService iam = mock(IamService.class);
    private final IamRolePolicyCfnProvisioner provisioner = new IamRolePolicyCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void putsThePolicyOnTheRoleAndRefIsTheRegistryIdentifier() throws Exception {
        StackResource r = resource();
        provisioner.provision(r, props("""
                {"RoleName": "web-role", "PolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17",
                                    "Statement": [{"Effect": "Allow", "Action": "s3:GetObject", "Resource": "*"}]}}
                """), ctx(null));

        assertEquals("app-policy|web-role", r.getPhysicalId());
        verify(iam).putRolePolicy(eq("web-role"), eq("app-policy"), argThatContains("s3:GetObject"));
        assertNull(r.getAttributes().get("Arn"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void aMissingNameRoleOrDocumentIsRejected() throws Exception {
        AwsException noName = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"RoleName": "web-role", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """), ctx(null)));
        assertEquals("ValidationError", noName.getErrorCode());
        assertThat(noName.getMessage(), containsString("PolicyName"));

        AwsException noRole = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"PolicyName": "app-policy", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """), ctx(null)));
        assertEquals("ValidationError", noRole.getErrorCode());
        assertThat(noRole.getMessage(), containsString("RoleName"));

        AwsException noDocument = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props("""
                {"RoleName": "web-role", "PolicyName": "app-policy"}
                """), ctx(null)));
        assertEquals("ValidationError", noDocument.getErrorCode());
        assertThat(noDocument.getMessage(), containsString("PolicyDocument"));
        verify(iam, never()).putRolePolicy(any(), any(), any());
    }

    @Test
    void aChangedDocumentIsPutInPlace() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("app-policy|web-role");

        provisioner.provision(r, props("""
                {"RoleName": "web-role", "PolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17",
                                    "Statement": [{"Effect": "Allow", "Action": "s3:PutObject", "Resource": "*"}]}}
                """), ctx("app-policy|web-role"));

        assertEquals("app-policy|web-role", r.getPhysicalId());
        verify(iam).putRolePolicy(eq("web-role"), eq("app-policy"), argThatContains("s3:PutObject"));
        assertFalse(provisioner.hasReplacementUpdate(r));
        verify(iam, never()).deleteRolePolicy(any(), any());
    }

    @Test
    void movingThePolicyToAnotherRoleReplacesItAndTheCommittedUpdateDeletesTheOldOne() throws Exception {
        StackResource r = resource();
        r.setPhysicalId("app-policy|web-role");

        provisioner.provision(r, props("""
                {"RoleName": "api-role", "PolicyName": "app-policy",
                 "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                """), ctx("app-policy|web-role"));

        assertEquals("app-policy|api-role", r.getPhysicalId());
        verify(iam).putRolePolicy(eq("api-role"), eq("app-policy"), any());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("app-policy|web-role", provisioner.updateCleanupPhysicalId(r));
        verify(iam, never()).deleteRolePolicy(any(), any());

        provisioner.completeUpdate(r);
        verify(iam).deleteRolePolicy("web-role", "app-policy");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void deleteRemovesThePolicyFromItsRoleAndToleratesOneAlreadyGone() {
        StackResource r = resource();
        r.setPhysicalId("app-policy|web-role");
        provisioner.delete(r, "us-east-1");
        verify(iam).deleteRolePolicy("web-role", "app-policy");

        doThrow(new AwsException("NoSuchEntity", "gone", 404)).when(iam).deleteRolePolicy("web-role", "app-policy");
        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));

        doThrow(new AwsException("AccessDenied", "nope", 403)).when(iam).deleteRolePolicy("web-role", "app-policy");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));
        assertEquals("AccessDenied", failure.getErrorCode());
    }

    private static String argThatContains(String text) {
        return argThat(document -> document != null && document.contains(text));
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.toString();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("RolePolicy");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
