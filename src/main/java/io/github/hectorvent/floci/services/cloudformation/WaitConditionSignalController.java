package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Receives the signals sent to a wait condition handle's URL.
 *
 * <p>Stands in for the presigned Amazon S3 URL AWS gives a {@code AWS::CloudFormation::WaitConditionHandle}
 * (CloudFormation User Guide, Create wait conditions in a CloudFormation template): a {@code PUT} whose
 * body is the wait condition signal JSON, {@code Status} {@code SUCCESS} or {@code FAILURE} with a
 * {@code UniqueId}, {@code Data} and {@code Reason}, and whose {@code Content-Type} is empty or
 * omitted, which is why no media type is required here. The handle is everything after the prefix,
 * the region, account, stack, logical id and uuid the handle's URL names, kept encoded as the URL
 * carries it.
 *
 * <p>The literal {@code /cloudformation-waitcondition/...} path takes precedence over the S3
 * controller's {@code /{bucket}} template, as {@code /cfn-response/...} does.
 */
@Path(WaitConditionSignalController.PATH)
public class WaitConditionSignalController {

    /** The path every handle URL starts with, after Floci's base URL. */
    public static final String PATH = "/cloudformation-waitcondition";

    private static final Logger LOG = Logger.getLogger(WaitConditionSignalController.class);

    private final WaitConditionSignalStore store;
    private final ObjectMapper objectMapper;

    @Inject
    public WaitConditionSignalController(WaitConditionSignalStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/{handle: .+}")
    public Response signal(@Encoded @PathParam("handle") String handle, String body) {
        JsonNode signal;
        try {
            signal = objectMapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            LOG.warnv("Wait condition handle {0} was sent a body that is not JSON: {1}", handle, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String status = signal == null ? null : signal.path("Status").asText(null);
        if (!"SUCCESS".equals(status) && !"FAILURE".equals(status)) {
            LOG.warnv("Wait condition handle {0} was sent no signal: Status must be SUCCESS or FAILURE", handle);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        store.receive(handle, new WaitConditionSignalStore.Signal(status,
                signal.path("UniqueId").asText(""), text(signal.get("Data")), text(signal.get("Reason"))));
        return Response.ok().build();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}
