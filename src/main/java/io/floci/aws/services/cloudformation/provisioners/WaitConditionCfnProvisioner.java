package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.WaitConditionSignalController;
import io.floci.aws.services.cloudformation.WaitConditionSignalStore;
import io.floci.aws.services.cloudformation.WaitConditionSignalStore.WaitConditionFailedException;
import io.floci.aws.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Provisions {@code AWS::CloudFormation::WaitCondition}: the resource stays {@code CREATE_IN_PROGRESS}, and so
 * holds its stack's create, until the handle its {@code Handle} names has received {@code Count} success
 * signals (one when {@code Count} is absent), each with its own {@code UniqueId}. A failure signal fails it with
 * the signal's {@code Reason}, and so does {@code Timeout} seconds passing first, as the CloudFormation User
 * Guide's page on wait conditions has it; the stack then rolls back as for any failed resource. A signal the
 * handle received before the condition began waiting counts, as one sent by a resource the condition
 * {@code DependsOn} would be.
 *
 * <p>{@code Ref} and {@code Fn::GetAtt Id} are the stack's id with the logical id after it, a name unique to the
 * resource; {@code Fn::GetAtt Data} is the JSON object of each success signal's {@code UniqueId} and
 * {@code Data}, the text the User Guide shows an output of it to be. The type's properties take no update, so an
 * update keeps the condition as it stands and waits on nothing. A condition with no {@code Handle} is the form a
 * {@code CreationPolicy} takes, whose signals Floci does not receive, so it completes at once.
 *
 * <p>Provisioning runs on the stack operation's own thread, so the wait blocks that operation and nothing else:
 * the handle's URL is served on the request threads, and the resource the condition depends on has already
 * completed.
 */
@ApplicationScoped
public class WaitConditionCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(WaitConditionCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HANDLE_ATTR = "__FlociWaitConditionHandle";
    /** The longest Timeout the type takes: twelve hours. */
    private static final int MAX_TIMEOUT_SECONDS = 43_200;

    private final WaitConditionSignalStore signals;

    @Inject
    public WaitConditionCfnProvisioner(WaitConditionSignalStore signals) {
        this.signals = signals;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CloudFormation::WaitCondition");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            r.setPhysicalId(ctx.priorPhysicalId());
            r.getAttributes().putIfAbsent("Id", ctx.priorPhysicalId());
            return;
        }
        String id = stackId(ctx) + "/" + r.getLogicalId();
        String handleUrl = ctx.resolveOptional(props, "Handle");
        String data = "{}";
        if (handleUrl == null || handleUrl.isBlank()) {
            LOG.debugv("Wait condition {0} names no Handle; it completes without waiting", r.getLogicalId());
        } else {
            String handle = handleOf(handleUrl, r);
            int count = count(ctx.resolveOptionalInt(props, "Count"));
            Duration timeout = timeout(ctx.resolveOptional(props, "Timeout"));
            r.getAttributes().put(HANDLE_ATTR, handle);
            data = await(r, handle, count, timeout);
        }
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
        r.getAttributes().put("Data", data);
    }

    /** Forgets the signals of the handle the deleted condition waited on. */
    @Override
    public void delete(StackResource r, String region) {
        String handle = r.getAttributes() == null ? null : r.getAttributes().get(HANDLE_ATTR);
        if (handle != null) {
            signals.discard(handle);
        }
    }

    private String await(StackResource r, String handle, int count, Duration timeout) {
        try {
            Map<String, String> received = signals.await(handle, count, timeout);
            return MAPPER.writeValueAsString(received);
        } catch (WaitConditionFailedException | TimeoutException e) {
            throw new AwsException("ValidationError", e.getMessage(), 400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("InternalFailure", "Wait condition " + r.getLogicalId()
                    + " was interrupted while it waited for signals", 500);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the signal data of " + r.getLogicalId(), e);
        }
    }

    /** The part of the handle's URL after the signal path, which keys its signals. */
    private static String handleOf(String handleUrl, StackResource r) {
        String marker = WaitConditionSignalController.PATH + "/";
        int at = handleUrl.indexOf(marker);
        if (at < 0) {
            throw new AwsException("ValidationError", "Handle of wait condition " + r.getLogicalId()
                    + " is not the URL of an AWS::CloudFormation::WaitConditionHandle: " + handleUrl, 400);
        }
        String handle = handleUrl.substring(at + marker.length());
        int query = handle.indexOf('?');
        return query < 0 ? handle : handle.substring(0, query);
    }

    private static int count(Integer count) {
        if (count == null) {
            return 1;
        }
        if (count < 1) {
            throw new AwsException("ValidationError", "Count of a wait condition must be at least 1.", 400);
        }
        return count;
    }

    private static Duration timeout(String timeout) {
        if (timeout == null || timeout.isBlank()) {
            return Duration.ofSeconds(MAX_TIMEOUT_SECONDS);
        }
        try {
            int seconds = Integer.parseInt(timeout.trim());
            if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
                throw new AwsException("ValidationError", "Timeout of a wait condition must be between 1 and "
                        + MAX_TIMEOUT_SECONDS + " seconds.", 400);
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "Timeout of a wait condition must be a number of seconds.",
                    400);
        }
    }

    private static String stackId(ProvisionContext ctx) {
        String stackId = ctx.engine().stackId();
        return stackId == null || stackId.isBlank() ? ctx.stackName() : stackId;
    }
}
