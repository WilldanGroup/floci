package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Network;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.FileTrees;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.codebuild.BuildspecParser.ParsedArtifacts;
import io.floci.aws.services.codebuild.BuildspecParser.ParsedBuildspec;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.BuildPhase;
import io.floci.aws.services.codebuild.model.Project;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.services.ssm.model.Parameter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs a build in CodeBuild's local agent ({@link CodeBuildAgent}), the image AWS publishes for running a
 * build as CodeBuild runs it: the agent's executor runs the buildspec's phases in a container of the
 * project's image, under the shell the buildspec names, and prints the build's log, which Floci reads for
 * the build's phases and sends to CloudWatch Logs. Floci records the phases before the agent's own
 * (SUBMITTED, QUEUED, PROVISIONING) and after them (FINALIZING, COMPLETED), and uploads the artifacts the
 * agent collected.
 */
@ApplicationScoped
public class CodeBuildRunner implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(CodeBuildRunner.class);
    private static final String DEFAULT_DOCKER_SOCKET = "/var/run/docker.sock";
    private static final String UNIX_SCHEME = "unix://";
    private static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";
    private static final int STOP_TIMEOUT_SECONDS = 5;

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final S3Service s3Service;
    private final SsmService ssmService;
    private final SecretsManagerService secretsManagerService;
    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final RegionResolver regionResolver;
    private final ContainerReachableEndpoint containerEndpoint;
    private final CodeBuildRoleCredentials roleCredentials;

    private final ConcurrentHashMap<String, RunningAgent> runningAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();
    private volatile String hostArchitecture;

    @Inject
    public CodeBuildRunner(DockerClient dockerClient,
                           ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerLogStreamer logStreamer,
                           S3Service s3Service,
                           SsmService ssmService,
                           SecretsManagerService secretsManagerService,
                           EmulatorConfig config,
                           ContainerDetector containerDetector,
                           RegionResolver regionResolver,
                           ContainerReachableEndpoint containerEndpoint,
                           CodeBuildRoleCredentials roleCredentials) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.s3Service = s3Service;
        this.ssmService = ssmService;
        this.secretsManagerService = secretsManagerService;
        this.config = config;
        this.containerDetector = containerDetector;
        this.regionResolver = regionResolver;
        this.containerEndpoint = containerEndpoint;
        this.roleCredentials = roleCredentials;
    }

    /** One build's agent: its container, and the compose project of the containers it started. */
    record RunningAgent(String containerId, String composeProject) {
    }

    /** How the agent's container ended: its exit code, or that the build's timeout came first. */
    record AgentExit(Integer exitCode, boolean timedOut) {
    }

    /**
     * Stops every in-flight build's agent and the containers it started on emulator shutdown; without
     * this they outlive the process as orphans. Build state is transient, so there is no persisted status
     * to update.
     */
    @Override
    public void stopManagedContainers() {
        for (Map.Entry<String, RunningAgent> entry : new LinkedHashMap<>(runningAgents).entrySet()) {
            if (runningAgents.remove(entry.getKey(), entry.getValue())) {
                try {
                    lifecycleManager.stopAndRemove(entry.getValue().containerId(), null);
                } catch (Exception e) {
                    LOG.warnv("Failed to stop the CodeBuild agent of build {0} on shutdown: {1}",
                            entry.getKey(), e.getMessage());
                }
                removeComposeProject(entry.getValue().composeProject());
            }
        }
    }

    public void startBuild(String region, Build build, Project project, String buildspecOverride) {
        String executionId = build.getArn();
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(executionId, stopFlag);
        Thread.ofVirtual().start(() -> runBuild(region, build, project, buildspecOverride, stopFlag, executionId));
    }

    public void stopBuild(String executionId) {
        AtomicBoolean flag = stopFlags.get(executionId);
        if (flag != null) {
            flag.set(true);
        }
        RunningAgent agent = runningAgents.get(executionId);
        if (agent != null) {
            try {
                dockerClient.stopContainerCmd(agent.containerId()).withTimeout(STOP_TIMEOUT_SECONDS).exec();
            } catch (Exception e) {
                LOG.debugv("Error stopping the CodeBuild agent {0}: {1}", agent.containerId(), e.getMessage());
            }
        }
    }

    /**
     * Runs one build to its end. The build is recorded complete only once everything it made is gone, its
     * agent's containers and volumes and its working folder, so a caller that reads it complete finds
     * nothing of it left.
     */
    private void runBuild(String region, Build build, Project project,
                          String buildspecOverride, AtomicBoolean stopFlag, String executionId) {
        BuildResources resources = new BuildResources();
        String status;
        try {
            status = execute(region, build, project, buildspecOverride, stopFlag, executionId, resources);
        } catch (Exception e) {
            LOG.error("Unexpected error in build " + build.getId(), e);
            recordFault(build, e);
            status = "FAULT";
        } finally {
            try {
                release(executionId, resources);
            } catch (RuntimeException e) {
                LOG.warnv("Could not release what build {0} made: {1}", build.getId(), e.getMessage());
            }
        }
        finishWith(build, status);
    }

    /** What a build has made that its end removes. */
    private static final class BuildResources {
        private Path buildFolder;
        private String agentId;
        private String composeProject;
        private Closeable logHandle;
    }

    /** Runs the build's phases; returns the status it ends with. */
    private String execute(String region, Build build, Project project, String buildspecOverride,
                           AtomicBoolean stopFlag, String executionId, BuildResources resources) throws Exception {
        String buildId = build.getId();

        beginPhase(build, "SUBMITTED");
        completePhase(build, "SUBMITTED", "SUCCEEDED");

        if (stopFlag.get()) {
            return "STOPPED";
        }

        beginPhase(build, "QUEUED");
        completePhase(build, "QUEUED", "SUCCEEDED");

        if (stopFlag.get()) {
            return "STOPPED";
        }

        // PROVISIONING: a host of the build's architecture, and the build's own session of its
        // project's service role, before anything of its buildspec runs.
        beginPhase(build, "PROVISIONING");
        String architecture = hostArchitecture();
        Optional<String> refusal = CodeBuildAgent.architectureRefusal(project.getName(),
                environmentType(build, project), architecture);
        if (refusal.isPresent()) {
            completePhaseWithError(build, "PROVISIONING", "FAILED", "CLIENT_ERROR", refusal.get());
            return "FAILED";
        }
        Optional<String> agentImage = config.services().codebuild().agentImage()
                .filter(image -> !image.isBlank())
                .or(() -> CodeBuildAgent.imageFor(architecture));
        if (agentImage.isEmpty()) {
            completePhaseWithError(build, "PROVISIONING", "FAILED", "CLIENT_ERROR",
                    "AWS publishes no CodeBuild local agent for this Docker host's architecture, "
                            + architecture + ", and floci.services.codebuild.agent-image names none");
            return "FAILED";
        }
        Optional<String> network = containerBuilder.resolveDockerNetwork(config.services().codebuild().dockerNetwork());
        CodeBuildRoleCredentials.BuildCredentials credentials;
        try {
            credentials = roleCredentials.provision(executionId, buildId, project.getServiceRole(),
                    accountOf(project), network);
        } catch (CodeBuildRoleCredentials.RoleNotFoundException e) {
            completePhaseWithError(build, "PROVISIONING", "FAILED", "CLIENT_ERROR", e.getMessage());
            return "FAILED";
        } catch (RuntimeException e) {
            completePhaseWithError(build, "PROVISIONING", "FAILED", "FAULT_ERROR", e.getMessage());
            throw e;
        }
        completePhase(build, "PROVISIONING", "SUCCEEDED");

        if (stopFlag.get()) {
            return "STOPPED";
        }

        // DOWNLOAD_SOURCE: the source and the buildspec, in a working folder the agent binds from the
        // Docker host. The agent completes the phase once its executor has read them.
        beginPhase(build, "DOWNLOAD_SOURCE");
        Owner owner = Owner.of(createWorkspace());
        Path buildFolder = owner.give(Files.createTempDirectory(owner.folder(), folderPrefix(buildId)));
        resources.buildFolder = buildFolder;
        Path source = owner.give(Files.createDirectory(buildFolder.resolve("src")));
        Path artifacts = owner.give(Files.createDirectory(buildFolder.resolve("artifacts")));

        String buildspecContent;
        ParsedBuildspec buildspec;
        try {
            buildspecContent = resolveAndAcquireSource(build, project, buildspecOverride, source, owner);
            buildspec = BuildspecParser.parse(buildspecContent);
        } catch (AwsException e) {
            completePhaseWithError(build, "DOWNLOAD_SOURCE", "FAILED", e.getMessage());
            return "FAILED";
        }

        String logGroup = "/aws/codebuild/" + project.getName();
        String logStream = logStreamer.generateLogStreamName(buildId.replace(":", "/"));
        Map<String, Object> logsMap = new HashMap<>();
        logsMap.put("groupName", logGroup);
        logsMap.put("streamName", logStream);
        logsMap.put("cloudWatchLogsArn", AwsArnUtils.Arn.of("logs", region, regionResolver.getAccountId(),
                "log-group:" + logGroup + ":log-stream:" + logStream).toString());
        build.setLogs(logsMap);
        String account = accountOf(project);
        logStreamer.ensureLogGroupAndStreamForAccount(account, logGroup, logStream, region);

        String image = build.getEnvironment() != null && build.getEnvironment().getImage() != null
                ? build.getEnvironment().getImage()
                : project.getEnvironment().getImage();
        boolean privileged = (project.getEnvironment() != null
                && Boolean.TRUE.equals(project.getEnvironment().getPrivilegedMode()))
                || (build.getEnvironment() != null
                && Boolean.TRUE.equals(build.getEnvironment().getPrivilegedMode()));

        Path buildspecFile = owner.write(buildFolder.resolve("buildspec.yml"),
                BuildspecParser.withoutResolvedVariables(buildspecContent));
        ContainerSpec resolvers = containerBuilder.newContainer(image)
                .withEmbeddedDns()
                .withHostDockerInternalOnLinux()
                .build();
        CodeBuildAgent.Plan plan = new CodeBuildAgent.Plan(
                buildVariables(region, build, project, buildspec, logStream, credentials.env()),
                network.filter(ContainerSpec::acceptsLinkLocalIps),
                resolvers.dnsServers() == null ? List.of() : resolvers.dnsServers(),
                resolvers.extraHosts() == null ? List.of() : resolvers.extraHosts(),
                agentLabels(buildId, region), buildspecFile);
        Path override = owner.write(buildFolder.resolve("docker-compose.override.yml"),
                CodeBuildAgent.composeOverride(plan));
        Path wrapper = owner.write(buildFolder.resolve("docker-compose"), CodeBuildAgent.COMPOSE_WRAPPER);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        String composeProject = CodeBuildAgent.composeProject(buildFolder.getFileName().toString());
        resources.composeProject = composeProject;

        ContainerSpec agentSpec = agentSpec(agentImage.get(), image, privileged, build, source, artifacts,
                wrapper, override, composeProject, region);
        String agentId = startAgent(agentSpec, plan);
        resources.agentId = agentId;
        runningAgents.put(executionId, new RunningAgent(agentId, composeProject));

        if (stopFlag.get()) {
            return "STOPPED";
        }

        AgentPhases phases = new AgentPhases(build);
        resources.logHandle = logStreamer.followLines(agentId, line -> {
            Optional<String> logLine = CodeBuildAgent.buildLogLine(line);
            if (logLine.isEmpty()) {
                LOG.debugv("[codebuild:{0}] {1}", buildId, line);
                return;
            }
            LOG.infov("[codebuild:{0}] {1}", buildId, logLine.get());
            logStreamer.streamToCloudWatchLogsForAccount(account, logGroup, logStream, region, logLine.get());
            phases.accept(logLine.get());
        });

        int timeoutMinutes = build.getTimeoutInMinutes() != null ? build.getTimeoutInMinutes() : 60;
        AgentExit exit = awaitAgent(agentId, timeoutMinutes);
        if (resources.logHandle != null) {
            lifecycleManager.closeLogStreamAfterContainerStop(resources.logHandle);
            resources.logHandle = null;
        }

        if (stopFlag.get()) {
            return "STOPPED";
        }
        if (exit.timedOut()) {
            phases.timedOut(timeoutMinutes);
            return "TIMED_OUT";
        }
        if (!phases.sawPhase()) {
            completePhaseWithError(build, "DOWNLOAD_SOURCE", "FAILED", "FAULT_ERROR",
                    "CodeBuild's local agent " + agentImage.get() + " ended with exit code " + exit.exitCode()
                            + " and ran no phase");
            return "FAULT";
        }
        boolean buildFailed = phases.failed();

        if (phases.succeeded("UPLOAD_ARTIFACTS")) {
            try {
                uploadArtifacts(build, project, buildspec.artifacts(), artifacts.resolve("artifacts.zip"));
            } catch (Exception e) {
                LOG.warnv("Artifact upload failed for build {0}: {1}", buildId, e.getMessage());
                completePhaseWithError(build, "UPLOAD_ARTIFACTS", "FAILED", "CLIENT_ERROR", e.getMessage());
                buildFailed = true;
            }
        }

        beginPhase(build, "FINALIZING");
        completePhase(build, "FINALIZING", "SUCCEEDED");
        beginPhase(build, "COMPLETED");
        completePhase(build, "COMPLETED", buildFailed ? "FAILED" : "SUCCEEDED");
        return buildFailed ? "FAILED" : "SUCCEEDED";
    }

    /** Records an unexpected failure as the build's COMPLETED phase, failed with its message. */
    private static void recordFault(Build build, Exception e) {
        if (build.getPhases() == null) {
            build.setPhases(new ArrayList<>());
        }
        boolean hasCompleted = build.getPhases().stream()
                .anyMatch(p -> "COMPLETED".equals(p.getPhaseType()));
        if (!hasCompleted) {
            BuildPhase completedPhase = new BuildPhase();
            completedPhase.setPhaseType("COMPLETED");
            completedPhase.setPhaseStatus("FAILED");
            completedPhase.setStartTime(EpochSeconds.fromMillis(System.currentTimeMillis()));
            completedPhase.setEndTime(EpochSeconds.fromMillis(System.currentTimeMillis()));
            completedPhase.setDurationInSeconds(0L);
            if (e.getMessage() != null) {
                completedPhase.setContexts(List.of(Map.of(
                        "statusCode", "FAULT_ERROR",
                        "message", e.getMessage()
                )));
            }
            build.getPhases().add(completedPhase);
        }
    }

    /**
     * Removes what the build made, however it ended: its session first, then its agent, then what the
     * agent's docker-compose started, which an agent stopped or timed out mid-build leaves running and
     * which holds the project's volumes, and last its working folder, once nothing that could write into
     * it is running.
     */
    private void release(String executionId, BuildResources resources) {
        stopFlags.remove(executionId);
        roleCredentials.revoke(executionId);
        if (resources.agentId != null) {
            runningAgents.remove(executionId);
            lifecycleManager.stopAndRemove(resources.agentId, resources.logHandle);
        } else if (resources.logHandle != null) {
            lifecycleManager.closeLogStreamAfterContainerStop(resources.logHandle);
        }
        if (resources.composeProject != null) {
            removeComposeProject(resources.composeProject);
        }
        roleCredentials.releaseAddress(executionId);
        if (resources.buildFolder != null) {
            deleteDirectory(resources.buildFolder);
        }
    }

    /** The type of environment the build runs on: the start's override, else the project's. */
    private static String environmentType(Build build, Project project) {
        if (build.getEnvironment() != null && build.getEnvironment().getType() != null) {
            return build.getEnvironment().getType();
        }
        return project.getEnvironment() != null ? project.getEnvironment().getType() : null;
    }

    /**
     * The Docker host's architecture, which every build runs on: the daemon's own word for it, or this
     * JVM's where the daemon does not answer, as nothing here runs emulated.
     */
    String hostArchitecture() {
        String known = hostArchitecture;
        if (known != null) {
            return known;
        }
        String reported = null;
        try {
            reported = dockerClient.infoCmd().exec().getArchitecture();
        } catch (RuntimeException e) {
            LOG.debugv("Docker did not report its architecture: {0}", e.getMessage());
        }
        if (reported == null || reported.isBlank()) {
            reported = System.getProperty("os.arch");
        }
        known = CodeBuildAgent.normalizeArchitecture(reported);
        hostArchitecture = known;
        return known;
    }

    private Path createWorkspace() throws IOException {
        Path workspace = CodeBuildAgent.workspace(config.services().codebuild().agentWorkspace(),
                Optional.ofNullable(System.getenv("SLUICE_TMP")), config.port());
        Files.createDirectories(workspace);
        return workspace;
    }

    private static String folderPrefix(String buildId) {
        return buildId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-") + "-";
    }

    /** Floci's labels and the build's identity, on the agent's containers and its project's volumes. */
    private Map<String, String> agentLabels(String buildId, String region) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.putAll(ContainerStorageHelper.resourceIdentityLabels(
                "codebuild", buildId, regionResolver.getAccountId(), region));
        return ContainerStorageHelper.CONTAINER_LABEL_ALIASES.withLegacyAliases(labels);
    }

    /** The agent's container, handed what {@code codebuild_build.sh} hands it. */
    private ContainerSpec agentSpec(String agentImage, String image, boolean privileged, Build build, Path source,
                                    Path artifacts, Path wrapper, Path override, String composeProject,
                                    String region) {
        String resolvedAgentImage = containerBuilder.resolveImage(agentImage);
        ContainerBuilder.Builder builder = containerBuilder.newContainer(agentImage)
                .withEnv("IMAGE_NAME", image)
                .withEnv("SOURCE", source.toString())
                .withEnv("ARTIFACTS", artifacts.toString())
                .withEnv("BUILDSPEC", CodeBuildAgent.BUILDSPEC_PATH)
                .withEnv("INITIATOR", build.getInitiator() != null ? build.getInitiator() : "user")
                .withEnv("LOCAL_AGENT_IMAGE_NAME", resolvedAgentImage != null ? resolvedAgentImage : agentImage)
                .withEnv("COMPOSE_PROJECT_NAME", composeProject)
                .withBind(dockerSocket(), DEFAULT_DOCKER_SOCKET)
                .withReadOnlyBind(wrapper.toString(), CodeBuildAgent.WRAPPER_PATH)
                .withReadOnlyBind(override.toString(), CodeBuildAgent.OVERRIDE_PATH)
                .withDockerNetwork(config.services().codebuild().dockerNetwork())
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "codebuild", build.getId(), regionResolver.getAccountId(), region));
        if (privileged) {
            builder.withEnv("DOCKER_PRIVILEGED_MODE", "TRUE");
        }
        return builder.build();
    }

    /** The Docker daemon's socket on its host, which the agent's docker-compose drives. */
    private String dockerSocket() {
        String host = config.docker() != null ? config.docker().dockerHost() : null;
        return host != null && host.startsWith(UNIX_SCHEME)
                ? host.substring(UNIX_SCHEME.length()) : DEFAULT_DOCKER_SOCKET;
    }

    /** Starts the agent's container; returns its id. */
    String startAgent(ContainerSpec agentSpec, CodeBuildAgent.Plan plan) {
        return lifecycleManager.createAndStart(agentSpec).containerId();
    }

    /** Waits for the agent's container to end, for no longer than the build's timeout. */
    AgentExit awaitAgent(String containerId, int timeoutMinutes) {
        try {
            Integer status = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(timeoutMinutes, TimeUnit.MINUTES);
            return new AgentExit(status, false);
        } catch (DockerClientException e) {
            LOG.debugv("CodeBuild agent {0} did not end within {1} minutes: {2}", containerId, timeoutMinutes,
                    e.getMessage());
            return new AgentExit(null, true);
        }
    }

    /**
     * Removes what the agent's docker-compose made for one build, its agent and build containers, then
     * their network and volumes, which compose labels with the project. Best effort, each on its own: a
     * build's end must not fail over its cleanup.
     */
    void removeComposeProject(String composeProject) {
        List<String> selector = List.of(COMPOSE_PROJECT_LABEL + "=" + composeProject);
        try {
            for (Container container : dockerClient.listContainersCmd().withShowAll(true)
                    .withLabelFilter(selector).exec()) {
                try {
                    dockerClient.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
                } catch (Exception e) {
                    LOG.warnv("Could not remove container {0} of {1}: {2}", container.getId(), composeProject,
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not list the containers of {0}: {1}", composeProject, e.getMessage());
        }
        try {
            for (Network network : dockerClient.listNetworksCmd().withFilter("label", selector).exec()) {
                try {
                    dockerClient.removeNetworkCmd(network.getId()).exec();
                } catch (Exception e) {
                    LOG.warnv("Could not remove network {0} of {1}: {2}", network.getName(), composeProject,
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not list the networks of {0}: {1}", composeProject, e.getMessage());
        }
        try {
            List<String> volumes = new ArrayList<>();
            dockerClient.listVolumesCmd().withFilter("label", selector).exec().getVolumes()
                    .forEach(volume -> volumes.add(volume.getName()));
            for (String volume : volumes) {
                try {
                    dockerClient.removeVolumeCmd(volume).exec();
                } catch (Exception e) {
                    LOG.warnv("Could not remove volume {0} of {1}: {2}", volume, composeProject, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not list the volumes of {0}: {1}", composeProject, e.getMessage());
        }
    }

    private String resolveAndAcquireSource(Build build, Project project, String buildspecOverride, Path source,
                                           Owner owner) throws IOException {
        String sourceType = project.getSource() != null ? project.getSource().getType() : "NO_SOURCE";

        if ("S3".equals(sourceType) && project.getSource().getLocation() != null) {
            String location = project.getSource().getLocation();
            int slash = location.indexOf('/');
            if (slash > 0) {
                String bucket = location.substring(0, slash);
                String key = location.substring(slash + 1);
                try {
                    S3Object obj = s3Service.getObject(bucket, key);
                    if (obj != null && obj.getData() != null) {
                        extractZip(obj.getData(), source, owner);
                    }
                } catch (Exception e) {
                    LOG.warnv("Could not acquire S3 source {0}: {1}", location, e.getMessage());
                }
            }
        }

        if (buildspecOverride != null && !buildspecOverride.isBlank()) {
            return buildspecOverride;
        }
        if (project.getSource() != null && project.getSource().getBuildspec() != null
                && !project.getSource().getBuildspec().isBlank()) {
            return project.getSource().getBuildspec();
        }
        Path yml = source.resolve("buildspec.yml");
        if (Files.exists(yml)) {
            return Files.readString(yml);
        }
        Path yaml = source.resolve("buildspec.yaml");
        if (Files.exists(yaml)) {
            return Files.readString(yaml);
        }
        throw new AwsException("InvalidInputException", "No buildspec found in source or request", 400);
    }

    /** The account the project is in, which its role is looked up and its session minted in. */
    private String accountOf(Project project) {
        try {
            return AwsArnUtils.parse(project.getArn()).accountId();
        } catch (RuntimeException e) {
            return regionResolver.getAccountId();
        }
    }

    /**
     * The variables Floci hands the build, each as the agent hands a project's: what CodeBuild sets that
     * the agent does not, the role's session, the buildspec's Parameter Store and Secrets Manager entries,
     * then the project's and the start's, each later one taking a name's place. The buildspec's own
     * {@code env.variables} are the agent's to apply, under all of these, as CodeBuild orders them.
     */
    private Map<String, String> buildVariables(String region, Build build, Project project,
                                               ParsedBuildspec buildspec, String logStream,
                                               Map<String, String> credentials) {
        Map<String, String> env = new LinkedHashMap<>();

        env.put("CODEBUILD_BUILD_ID", build.getId());
        env.put("CODEBUILD_BUILD_ARN", build.getArn());
        env.put("CODEBUILD_BUILD_NUMBER", String.valueOf(build.getBuildNumber()));
        env.put("CODEBUILD_LOG_PATH", logStream);
        env.put("AWS_DEFAULT_REGION", region);
        env.put("AWS_REGION", region);
        env.put("AWS_ENDPOINT_URL", resolveEndpointUrl());
        // The project's role, ahead of everything a project, its buildspec or the build's start
        // sets, which still reaches the build as CodeBuild's own variables do.
        env.putAll(credentials);

        for (Map.Entry<String, String> e : buildspec.parameterStoreVars().entrySet()) {
            try {
                Parameter p = ssmService.getParameter(e.getValue(), region);
                env.put(e.getKey(), p.getValue());
            } catch (Exception ex) {
                LOG.debugv("Could not resolve SSM parameter {0}: {1}", e.getValue(), ex.getMessage());
            }
        }

        for (Map.Entry<String, String> e : buildspec.secretsManagerVars().entrySet()) {
            try {
                SecretVersion sv = secretsManagerService.getSecretValue(e.getValue(), null, null, region);
                env.put(e.getKey(), sv.getSecretString() != null ? sv.getSecretString() : "");
            } catch (Exception ex) {
                LOG.debugv("Could not resolve secret {0}: {1}", e.getValue(), ex.getMessage());
            }
        }

        if (project.getEnvironment() != null && project.getEnvironment().getEnvironmentVariables() != null) {
            for (Map<String, String> v : project.getEnvironment().getEnvironmentVariables()) {
                String name = v.get("name");
                String value = v.get("value");
                if (name != null) {
                    env.put(name, value != null ? value : "");
                }
            }
        }

        if (build.getEnvironment() != null && build.getEnvironment().getEnvironmentVariables() != null) {
            for (Map<String, String> v : build.getEnvironment().getEnvironmentVariables()) {
                String name = v.get("name");
                String value = v.get("value");
                if (name != null) {
                    env.put(name, value != null ? value : "");
                }
            }
        }
        return env;
    }

    String resolveEndpointUrl() {
        if (containerDetector.isRunningInContainer()) {
            String suffix = config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
            return "http://" + suffix + ":" + containerEndpoint.port();
        } else {
            return "http://host.docker.internal:" + containerEndpoint.port();
        }
    }

    /**
     * Uploads what the agent collected, the {@code artifacts.zip} it writes of the buildspec's
     * {@code artifacts} section, as the project's artifacts say: the archive itself, or each file in it
     * for {@code NONE} packaging.
     */
    private void uploadArtifacts(Build build, Project project, ParsedArtifacts artifacts, Path archive)
            throws IOException {
        String type = artifacts.type();
        if ("NO_ARTIFACTS".equals(type) && project.getArtifacts() != null) {
            type = project.getArtifacts().getType();
        }
        if (!"S3".equals(type) || !Files.isRegularFile(archive)) {
            return;
        }

        String packaging = artifacts.packaging();
        if ("ZIP".equals(packaging) && project.getArtifacts() != null
                && project.getArtifacts().getPackaging() != null) {
            packaging = project.getArtifacts().getPackaging();
        }

        String location = project.getArtifacts() != null ? project.getArtifacts().getLocation() : null;
        if (location == null || location.isBlank()) {
            return;
        }

        int slash = location.indexOf('/');
        String bucket = slash > 0 ? location.substring(0, slash) : location;
        String prefix = slash > 0 ? location.substring(slash + 1) : "";
        String artifactName = artifacts.name() != null ? artifacts.name()
                : project.getName() + "-" + build.getBuildNumber();

        byte[] zip = Files.readAllBytes(archive);
        if ("NONE".equalsIgnoreCase(packaging)) {
            try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry entry;
                while ((entry = entries.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String key = prefix.isBlank() ? entry.getName() : prefix + "/" + entry.getName();
                    byte[] data = entries.readAllBytes();
                    s3Service.putObject(bucket, key, data, guessContentType(entry.getName()), Map.of());
                }
            }
        } else {
            String key = prefix.isBlank() ? artifactName + ".zip" : prefix + "/" + artifactName + ".zip";
            s3Service.putObject(bucket, key, zip, "application/zip", Map.of());
        }
    }

    private void extractZip(byte[] data, Path dest, Owner owner) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = dest.resolve(entry.getName()).normalize();
                if (!target.startsWith(dest)) {
                    continue; // zip slip protection
                }
                if (entry.isDirectory()) {
                    owner.giveAll(dest, Files.createDirectories(target));
                } else {
                    owner.giveAll(dest, Files.createDirectories(target.getParent()));
                    owner.give(Files.write(target, zis.readAllBytes()));
                }
                zis.closeEntry();
            }
        }
    }

    private static void beginPhase(Build build, String phaseType) {
        BuildPhase phase = new BuildPhase();
        phase.setPhaseType(phaseType);
        phase.setPhaseStatus("IN_PROGRESS");
        phase.setStartTime(EpochSeconds.fromMillis(System.currentTimeMillis()));
        build.getPhases().add(phase);
        build.setCurrentPhase(phaseType);
    }

    private static void completePhase(Build build, String phaseType, String status) {
        findPhase(build, phaseType).ifPresent(p -> {
            double end = EpochSeconds.fromMillis(System.currentTimeMillis());
            p.setPhaseStatus(status);
            p.setEndTime(end);
            p.setDurationInSeconds(Math.round(end - p.getStartTime()));
        });
    }

    private static void completePhaseWithError(Build build, String phaseType, String status, String message) {
        completePhaseWithError(build, phaseType, status, "COMMAND_EXECUTION_ERROR", message);
    }

    private static void completePhaseWithError(Build build, String phaseType, String status, String statusCode,
                                               String message) {
        findPhase(build, phaseType).ifPresent(p -> {
            double end = EpochSeconds.fromMillis(System.currentTimeMillis());
            p.setPhaseStatus(status);
            p.setEndTime(end);
            p.setDurationInSeconds(Math.round(end - p.getStartTime()));
            if (message != null) {
                String truncated = message.substring(0, Math.min(message.length(), 1024));
                p.setContexts(List.of(Map.of("statusCode", statusCode, "message", truncated)));
            }
        });
    }

    private static void finishWith(Build build, String status) {
        build.setEndTime(EpochSeconds.fromMillis(System.currentTimeMillis()));
        build.setBuildComplete(true);
        build.setBuildStatus(status);
        build.setCurrentPhase("COMPLETED");
    }

    private static Optional<BuildPhase> findPhase(Build build, String phaseType) {
        return build.getPhases().stream()
                .filter(p -> phaseType.equals(p.getPhaseType()))
                .findFirst();
    }

    private void deleteDirectory(Path path) {
        try {
            FileTrees.delete(path);
        } catch (IOException e) {
            LOG.warnv("Could not delete the working folder {0}: {1}", path, e.getMessage());
        }
    }

    private static String guessContentType(String filename) {
        if (filename.endsWith(".json")) {
            return "application/json";
        }
        if (filename.endsWith(".xml")) {
            return "application/xml";
        }
        if (filename.endsWith(".html")) {
            return "text/html";
        }
        return "text/plain";
    }

    /**
     * The build's phases as the agent's executor reports them in the build's log: {@code Entering phase X}
     * as one starts, {@code Phase complete: X State: S} as it ends, and the context it ended with on the
     * line after, a phase the log never said it entered begun as it ends. Read on the log's own thread
     * while the build's waits on the agent.
     */
    static final class AgentPhases {

        private static final Pattern ENTERING = Pattern.compile(".*\\bEntering phase ([A-Z_]+)\\s*$");
        private static final Pattern COMPLETE =
                Pattern.compile(".*\\bPhase complete: ([A-Z_]+) State: ([A-Z_]+)\\s*$");
        private static final Pattern CONTEXT =
                Pattern.compile(".*\\bPhase context status code: (.*?) Message: (.*)$");

        private final Build build;
        private String lastCompleted;
        private boolean sawPhase;

        AgentPhases(Build build) {
            this.build = build;
        }

        synchronized void accept(String line) {
            Matcher entering = ENTERING.matcher(line);
            if (entering.matches()) {
                if (findPhase(build, entering.group(1)).isEmpty()) {
                    beginPhase(build, entering.group(1));
                }
                return;
            }
            Matcher complete = COMPLETE.matcher(line);
            if (complete.matches()) {
                String phase = complete.group(1);
                if (findPhase(build, phase).isEmpty()) {
                    beginPhase(build, phase);
                }
                completePhase(build, phase, complete.group(2));
                lastCompleted = phase;
                sawPhase = true;
                return;
            }
            Matcher context = CONTEXT.matcher(line);
            if (context.matches() && lastCompleted != null && !context.group(1).isBlank()) {
                String code = context.group(1).strip();
                String message = context.group(2).strip();
                findPhase(build, lastCompleted).ifPresent(phase -> phase.setContexts(
                        List.of(Map.of("statusCode", code,
                                "message", message.substring(0, Math.min(message.length(), 1024))))));
            }
        }

        synchronized boolean sawPhase() {
            return sawPhase;
        }

        synchronized boolean failed() {
            return build.getPhases().stream().anyMatch(phase -> "FAILED".equals(phase.getPhaseStatus()));
        }

        synchronized boolean succeeded(String phaseType) {
            return findPhase(build, phaseType).map(phase -> "SUCCEEDED".equals(phase.getPhaseStatus()))
                    .orElse(false);
        }

        /** Ends the phase the build was in when its timeout came, as CodeBuild ends it. */
        synchronized void timedOut(int timeoutMinutes) {
            String current = build.getCurrentPhase();
            if (current != null) {
                completePhaseWithError(build, current, "TIMED_OUT", "BUILD_TIMED_OUT",
                        "Build has timed out after " + timeoutMinutes + " minutes");
            }
        }
    }

    /**
     * The owner of the folder the agent's working folders are made in, who is given each thing Floci
     * makes there, while it is still empty: the agent binds them from the Docker host, where that folder
     * is a host user's, and Floci in a container commonly runs as root. Best effort where Floci may not
     * give a file away: what it makes is then its own user's.
     */
    record Owner(Path folder, Integer uid, Integer gid) {

        static Owner of(Path folder) {
            try {
                return new Owner(folder, (Integer) Files.getAttribute(folder, "unix:uid"),
                        (Integer) Files.getAttribute(folder, "unix:gid"));
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                LOG.debugv("Could not read the owner of {0}: {1}", folder, e.getMessage());
                return new Owner(folder, null, null);
            }
        }

        Path give(Path path) {
            if (uid == null || gid == null) {
                return path;
            }
            try {
                Files.setAttribute(path, "unix:uid", uid);
                Files.setAttribute(path, "unix:gid", gid);
            } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
                LOG.debugv("Could not give {0} to {1}:{2}: {3}", path, uid, gid, e.getMessage());
            }
            return path;
        }

        /** Gives {@code path} and every folder between it and {@code root}. */
        void giveAll(Path root, Path path) {
            for (Path current = path; current != null && current.startsWith(root) && !current.equals(root);
                 current = current.getParent()) {
                give(current);
            }
        }

        Path write(Path path, String content) throws IOException {
            Files.writeString(give(Files.createFile(path)), content, StandardCharsets.UTF_8);
            return path;
        }
    }
}
