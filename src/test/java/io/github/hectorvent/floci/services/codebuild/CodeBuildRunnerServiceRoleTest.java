package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.BuildPhase;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentialsServer;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A build runs as the role its project names as {@code serviceRole}, handed over the way CodeBuild
 * hands it: on a user-defined network, the container credentials endpoint's relative URI and no
 * keys, the endpoint served by a relay in the build container's own network namespace that forwards
 * to the credentials server; on the default bridge, the same session as keys. A build whose role
 * cannot be found never starts its agent.
 */
class CodeBuildRunnerServiceRoleTest {

    @TempDir
    Path workspace;

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE_NAME = "build-role";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/service-role/" + ROLE_NAME;
    private static final String NETWORK = "builds";
    private static final int CREDENTIALS_PORT = 51679;
    private static final Set<String> NO_KEYS = Set.of(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_CONTAINER_AUTHORIZATION_TOKEN",
            "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");

    @Test
    void aBuildOnAUserDefinedNetworkIsServedItsRoleAtTheRelativeUriAndHoldsNoKeys() throws Exception {
        Fixture fixture = fixture(Optional.of(NETWORK), true, List.of());

        fixture.run();

        Map<String, String> env = fixture.env();
        assertEquals(List.of(), env.keySet().stream().filter(NO_KEYS::contains).toList(),
                "the build's environment holds credentials of its own: " + env);
        String relativeUri = value(env, "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI")
                .orElseThrow(() -> new AssertionError("no AWS_CONTAINER_CREDENTIALS_RELATIVE_URI in " + env));
        assertTrue(relativeUri.startsWith("/"), relativeUri);
        assertTrue(!relativeUri.contains("://"), relativeUri);
        CodeBuildAgent.CredentialsRelay relay = fixture.plan().relay();
        assertEquals("flocihost", relay.host());
        assertEquals(CREDENTIALS_PORT, relay.port());

        SessionCredential session = fixture.issuedSession();
        assertEquals(ROLE_ARN, session.getRoleArn());
        assertEquals("AWSCodeBuild-7", session.getRoleSessionName());
        assertEquals(ACCOUNT, session.getOriginAccountId());
    }

    @Test
    void aBuildOnTheDefaultBridgeHoldsItsRolesSessionAsKeys() throws Exception {
        Fixture fixture = fixture(Optional.empty(), true, List.of());

        fixture.run();

        Map<String, String> env = fixture.env();
        SessionCredential session = fixture.issuedSession();
        assertEquals(Optional.of(session.getAccessKeyId()), value(env, "AWS_ACCESS_KEY_ID"), env::toString);
        assertEquals(Optional.of(session.getSecretAccessKey()), value(env, "AWS_SECRET_ACCESS_KEY"), env::toString);
        assertEquals(Optional.of(session.getSessionToken()), value(env, "AWS_SESSION_TOKEN"), env::toString);
        assertTrue(session.getAccessKeyId().startsWith("ASIA"), session.getAccessKeyId());
        assertEquals(ROLE_ARN, session.getRoleArn());
        assertEquals("AWSCodeBuild-7", session.getRoleSessionName());
        assertTrue(value(env, "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI").isEmpty(), env::toString);
        assertEquals(null, fixture.plan().relay());
    }

    @Test
    void credentialVariablesTheProjectSetsStillReachTheBuild() throws Exception {
        Fixture fixture = fixture(Optional.of(NETWORK), true, List.of(
                Map.of("name", "AWS_ACCESS_KEY_ID", "value", "project-key"),
                Map.of("name", "AWS_SESSION_TOKEN", "value", "project-token")));

        fixture.run();

        Map<String, String> env = fixture.env();
        assertEquals(Optional.of("project-key"), value(env, "AWS_ACCESS_KEY_ID"), env::toString);
        assertEquals(Optional.of("project-token"), value(env, "AWS_SESSION_TOKEN"), env::toString);
    }

    @Test
    void aBuildWhoseRoleIsNotFoundFailsBeforeItsFirstCommand() throws Exception {
        Fixture fixture = fixture(Optional.of(NETWORK), false, List.of());

        fixture.run();

        Build build = fixture.build();
        assertEquals("FAILED", build.getBuildStatus());
        BuildPhase failed = build.getPhases().stream()
                .filter(phase -> "FAILED".equals(phase.getPhaseStatus()))
                .findFirst().orElseThrow(() -> new AssertionError("no failed phase in " + phases(build)));
        assertTrue(String.valueOf(failed.getContexts().getFirst().get("message")).contains(ROLE_ARN),
                () -> failed.getPhaseType() + " " + failed.getContexts());
        verify(fixture.runner(), never()).startAgent(any(), any());
    }

    private static List<String> phases(Build build) {
        return build.getPhases().stream().map(p -> p.getPhaseType() + "=" + p.getPhaseStatus()).toList();
    }

    private static Optional<String> value(Map<String, String> env, String name) {
        return Optional.ofNullable(env.get(name));
    }

    private Fixture fixture(Optional<String> network, boolean roleExists, List<Map<String, String>> projectVariables) {
        DockerClient docker = mock(DockerClient.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(network);
        when(specBuilder.build()).thenReturn(new ContainerSpec("alpine:latest"));

        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("container", Map.of()));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().dockerNetwork()).thenReturn(network);
        when(config.services().codebuild().agentWorkspace()).thenReturn(Optional.of(workspace.toString()));
        when(config.services().codebuild().agentImage()).thenReturn(Optional.of("agent:latest"));
        when(config.services().ecs().taskRoleCredentials().ttlSeconds()).thenReturn(21600L);
        when(config.services().ecs().taskRoleCredentials().port()).thenReturn(CREDENTIALS_PORT);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT);
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.port()).thenReturn(4566);

        IamService iam = mock(IamService.class);
        IamRole role = new IamRole();
        role.setRoleId("AROABUILDROLE");
        role.setRoleName(ROLE_NAME);
        role.setArn(ROLE_ARN);
        when(iam.findRole(ACCOUNT, ROLE_NAME)).thenReturn(roleExists ? Optional.of(role) : Optional.empty());
        EcsTaskRoleCredentials credentials = new EcsTaskRoleCredentials(iam, config);
        EcsTaskRoleCredentialsServer server = mock(EcsTaskRoleCredentialsServer.class);
        when(server.start()).thenReturn(CompletableFuture.completedFuture(null));
        DockerHostResolver dockerHost = mock(DockerHostResolver.class);
        when(dockerHost.resolve()).thenReturn("flocihost");

        CodeBuildRunner runner = spy(new CodeBuildRunner(docker, containerBuilder, lifecycle, logStreamer,
                null, null, null, config, mock(ContainerDetector.class), regionResolver, endpoint,
                new CodeBuildRoleCredentials(credentials, server), dockerHost));
        doReturn("container").when(runner).startAgent(any(), any());
        doReturn(new CodeBuildRunner.AgentExit(0, false)).when(runner).awaitAgent(anyString(), anyInt());
        doNothing().when(runner).removeComposeProject(anyString());

        Build build = new Build();
        build.setId("project:7");
        build.setArn("arn:aws:codebuild:us-east-1:" + ACCOUNT + ":build/project:7");
        build.setBuildNumber(7L);
        build.setPhases(new ArrayList<>());

        Project project = new Project();
        project.setName("project");
        project.setArn("arn:aws:codebuild:us-east-1:" + ACCOUNT + ":project/project");
        project.setServiceRole(ROLE_ARN);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        source.setBuildspec("version: 0.2\nphases:\n  build:\n    commands:\n      - aws sts get-caller-identity\n");
        project.setSource(source);
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setImage("alpine:latest");
        environment.setEnvironmentVariables(projectVariables);
        project.setEnvironment(environment);

        return new Fixture(runner, build, project, specBuilder, lifecycle, iam);
    }

    private record Fixture(CodeBuildRunner runner, Build build, Project project, ContainerBuilder.Builder specBuilder,
                           ContainerLifecycleManager lifecycle, IamService iam) {

        void run() throws InterruptedException {
            runner.startBuild("us-east-1", build, project, null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Boolean.TRUE.equals(build.getBuildComplete()) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Boolean.TRUE.equals(build.getBuildComplete()), "build did not finish: " + phases(build));
        }

        /** What the build's agent was handed beside its own compose file. */
        CodeBuildAgent.Plan plan() {
            ArgumentCaptor<CodeBuildAgent.Plan> captor = ArgumentCaptor.forClass(CodeBuildAgent.Plan.class);
            verify(runner).startAgent(any(), captor.capture());
            return captor.getValue();
        }

        /** The variables the build's agent was handed. */
        Map<String, String> env() {
            return plan().variables();
        }

        /** The one session the build's start registered with IAM. */
        SessionCredential issuedSession() {
            ArgumentCaptor<SessionCredential> captor = ArgumentCaptor.forClass(SessionCredential.class);
            verify(iam).registerEcsTaskRoleSession(captor.capture());
            return captor.getValue();
        }
    }
}
