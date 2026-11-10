package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentialsServer;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hands a build a session of its project's service role, as CodeBuild does: "CodeBuild uses the
 * service role for all operations that are performed on your behalf" (AWS CodeBuild User Guide,
 * Allow CodeBuild to interact with other AWS services). CodeBuild hands it through the container
 * credentials provider, one variable, {@code AWS_CONTAINER_CREDENTIALS_RELATIVE_URI}, whose path
 * the SDKs read at {@code http://169.254.170.2}, and no keys in the build's environment.
 *
 * <p>The session is the role's, minted in the project's account and named
 * {@code AWSCodeBuild-<the build id after the project's name>}, as CodeBuild names it, so
 * {@code GetCallerIdentity} answers its {@code assumed-role} ARN and IAM enforcement evaluates the
 * role's policies. It is served by the same credentials server as an ECS task's role, which starts on
 * a build's need whatever ECS's own setting says, and reached from the build's container through a
 * relay in the container's own network namespace ({@link CodeBuildAgent}), which holds
 * {@code 169.254.170.2} on its loopback and forwards to the server at Floci's address on the build's
 * network. On Docker's default bridge, where the build's agent cannot join Floci's network, the same
 * session is handed as keys instead, and the build still runs as its role.
 *
 * <p>The role's trust policy is not evaluated: like an ECS task's, the session is minted for any
 * role that exists.
 */
@ApplicationScoped
public class CodeBuildRoleCredentials {

    static final String RELATIVE_URI = "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI";
    private static final String SESSION_PREFIX = "AWSCodeBuild-";
    private static final long SERVER_START_SECONDS = 30;

    private final EcsTaskRoleCredentials sessions;
    private final EcsTaskRoleCredentialsServer server;

    @Inject
    public CodeBuildRoleCredentials(EcsTaskRoleCredentials sessions, EcsTaskRoleCredentialsServer server) {
        this.sessions = sessions;
        this.server = server;
    }

    /**
     * Mints the build's session and starts what serves it, before the build's agent starts.
     *
     * @param buildArn the build's ARN, which owns the session until {@link #revoke}
     * @param network  the Docker network the build's containers join, empty for the default bridge
     * @throws RoleNotFoundException when the role names no role in the account; nothing is minted
     * @throws IllegalStateException when the credentials server cannot be started
     */
    public BuildCredentials provision(String buildArn, String buildId, String roleArn, String accountId,
                                      Optional<String> network) {
        String sessionName = SESSION_PREFIX + buildId.substring(buildId.indexOf(':') + 1);
        Optional<String> path = sessions.issue(buildArn, roleArn, accountId, sessionName, Instant.now());
        if (path.isEmpty()) {
            throw new RoleNotFoundException("The project's service role " + roleArn
                    + " was not found in account " + accountId + ", so the build ran none of its commands");
        }
        if (network.filter(ContainerSpec::acceptsLinkLocalIps).isEmpty()) {
            SessionCredential session = sessions.resolveByPath(path.get(), Instant.now()).orElseThrow(() ->
                    new IllegalStateException("The session of " + roleArn + " was revoked as it was minted"));
            Map<String, String> env = new LinkedHashMap<>();
            env.put("AWS_ACCESS_KEY_ID", session.getAccessKeyId());
            env.put("AWS_SECRET_ACCESS_KEY", session.getSecretAccessKey());
            env.put("AWS_SESSION_TOKEN", session.getSessionToken());
            return new BuildCredentials(env, false);
        }
        startServer();
        return new BuildCredentials(Map.of(RELATIVE_URI, path.get()), true);
    }

    /** Revokes every generation of the build's session, however the build ended. */
    public void revoke(String buildArn) {
        sessions.revoke(buildArn);
    }

    private void startServer() {
        try {
            server.start().get(SERVER_START_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the container credentials server", e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("The container credentials server did not start: " + cause.getMessage(), e);
        }
    }

    /**
     * What the build is given: the variables that hand it its session, and whether it reads that session
     * at the container credentials endpoint, which its agent's relay then serves.
     */
    public record BuildCredentials(Map<String, String> env, boolean servedAtEndpoint) {
    }

    /** A project's service role that names no role in the project's account. */
    public static final class RoleNotFoundException extends RuntimeException {
        RoleNotFoundException(String message) {
            super(message);
        }
    }
}
