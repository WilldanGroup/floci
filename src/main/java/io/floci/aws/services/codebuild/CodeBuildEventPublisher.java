package io.floci.aws.services.codebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.BuildPhase;
import io.floci.aws.services.codebuild.model.Project;
import io.floci.aws.services.codebuild.model.ProjectEnvironment;
import io.floci.aws.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts a {@code CodeBuild Build State Change} event on the default event bus of the build's account and
 * Region as a build starts, {@code IN_PROGRESS}, and as it ends, with the status it ended with, in the
 * shape of CodeBuild's own ("Build notifications input format reference", AWS CodeBuild User Guide).
 * Delivery is best effort: a failure is logged and never fails the build.
 */
@ApplicationScoped
public class CodeBuildEventPublisher {

    private static final Logger LOG = Logger.getLogger(CodeBuildEventPublisher.class);
    static final String SOURCE = "aws.codebuild";
    static final String DETAIL_TYPE = "CodeBuild Build State Change";
    /** A time inside the detail, as CodeBuild writes it there: {@code Sep 1, 2017 4:12:29 PM}, in UTC. */
    private static final DateTimeFormatter DETAIL_TIME =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a", Locale.US).withZone(ZoneOffset.UTC);

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeBuildEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
    }

    /** Sends the build's state change at {@code status}, as the build records it now. */
    public void buildStateChanged(Build build, Project project, String status) {
        try {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(build.getArn());
            String detail = objectMapper.writeValueAsString(detail(build, project, status));
            RequestScopes.runAs(arn.accountId(), arn.region(), () -> eventBridgeService.publishServiceEvent(
                    SOURCE, DETAIL_TYPE, detail, build.getArn(), arn.region()));
        } catch (Exception e) {
            LOG.warnv("Could not send the CodeBuild Build State Change of {0} at {1}: {2}", build.getId(), status,
                    e.getMessage());
        }
    }

    ObjectNode detail(Build build, Project project, String status) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("build-status", status);
        detail.put("project-name", build.getProjectName());
        detail.put("build-id", build.getArn());

        ObjectNode information = detail.putObject("additional-information");
        ObjectNode environment = information.putObject("environment");
        ProjectEnvironment projectEnvironment = project.getEnvironment();
        ProjectEnvironment buildEnvironment = build.getEnvironment();
        environment.put("image", first(buildEnvironment == null ? null : buildEnvironment.getImage(),
                projectEnvironment == null ? null : projectEnvironment.getImage()));
        environment.put("privileged-mode", Boolean.TRUE.equals(first(
                buildEnvironment == null ? null : buildEnvironment.getPrivilegedMode(),
                projectEnvironment == null ? null : projectEnvironment.getPrivilegedMode())));
        environment.put("compute-type", first(buildEnvironment == null ? null : buildEnvironment.getComputeType(),
                projectEnvironment == null ? null : projectEnvironment.getComputeType()));
        environment.put("type", first(buildEnvironment == null ? null : buildEnvironment.getType(),
                projectEnvironment == null ? null : projectEnvironment.getType()));
        ArrayNode variables = environment.putArray("environment-variables");
        for (Map<String, String> variable : declaredVariables(projectEnvironment, buildEnvironment)) {
            variables.addObject()
                    .put("name", variable.get("name"))
                    .put("type", variable.get("type") != null ? variable.get("type") : "PLAINTEXT")
                    .put("value", variable.get("value") != null ? variable.get("value") : "");
        }
        information.put("timeout-in-minutes", build.getTimeoutInMinutes() != null ? build.getTimeoutInMinutes() : 60);
        information.put("build-complete", !"IN_PROGRESS".equals(status));
        information.put("initiator", build.getInitiator() != null ? build.getInitiator() : "");
        information.put("build-start-time", time(build.getStartTime()));
        if (build.getSource() != null) {
            ObjectNode source = information.putObject("source");
            if (build.getSource().getLocation() != null) {
                source.put("location", build.getSource().getLocation());
            }
            source.put("type", build.getSource().getType());
        }
        if (build.getLogs() != null) {
            ObjectNode logs = information.putObject("logs");
            logs.put("group-name", String.valueOf(build.getLogs().get("groupName")));
            logs.put("stream-name", String.valueOf(build.getLogs().get("streamName")));
        }
        ArrayNode phases = information.putArray("phases");
        for (BuildPhase phase : build.getPhases() == null ? List.<BuildPhase>of() : List.copyOf(build.getPhases())) {
            ObjectNode listed = phases.addObject();
            if (!"COMPLETED".equals(phase.getPhaseType()) && phase.getEndTime() != null) {
                ArrayNode contexts = listed.putArray("phase-context");
                if (phase.getContexts() != null) {
                    for (Map<String, String> context : phase.getContexts()) {
                        contexts.add(context.get("statusCode") + ": " + context.get("message"));
                    }
                }
            }
            if (phase.getStartTime() != null) {
                listed.put("start-time", time(phase.getStartTime()));
            }
            if (!"COMPLETED".equals(phase.getPhaseType()) && phase.getEndTime() != null) {
                listed.put("end-time", time(phase.getEndTime()));
                listed.put("duration-in-seconds", phase.getDurationInSeconds() != null ? phase.getDurationInSeconds() : 0);
            }
            listed.put("phase-type", phase.getPhaseType());
            if (!"COMPLETED".equals(phase.getPhaseType()) && phase.getEndTime() != null) {
                listed.put("phase-status", phase.getPhaseStatus());
            }
        }

        detail.put("current-phase", build.getCurrentPhase());
        detail.put("current-phase-context", "[]");
        detail.put("version", "1");
        return detail;
    }

    private static List<Map<String, String>> declaredVariables(ProjectEnvironment project, ProjectEnvironment build) {
        List<Map<String, String>> declared = new ArrayList<>();
        if (project != null && project.getEnvironmentVariables() != null) {
            declared.addAll(project.getEnvironmentVariables());
        }
        if (build != null && build.getEnvironmentVariables() != null && build != project) {
            for (Map<String, String> variable : build.getEnvironmentVariables()) {
                declared.removeIf(existing -> variable.get("name") != null
                        && variable.get("name").equals(existing.get("name")));
                declared.add(variable);
            }
        }
        return declared;
    }

    private static <T> T first(T override, T declared) {
        return override != null ? override : declared;
    }

    private static String time(Double epochSeconds) {
        return epochSeconds == null ? "" : DETAIL_TIME.format(Instant.ofEpochMilli((long) (epochSeconds * 1000)));
    }
}
