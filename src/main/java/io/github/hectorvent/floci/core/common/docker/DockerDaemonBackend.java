package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Locale;

/**
 * What the Docker daemon says it runs on, read from {@code GET /info}'s {@code OperatingSystem}
 * ("Name of the host's operating system", Docker Engine API reference, System), which names
 * Docker Desktop on a Docker Desktop daemon whatever the machine under it.
 *
 * <p>Floci's own {@code os.name} cannot tell: Floci in a container on a Mac sees Linux. The daemon's
 * answer is read once and shared; an answer the daemon could not give is not kept, so the next
 * caller asks again, and until then counts as no backend in particular.
 */
@ApplicationScoped
public class DockerDaemonBackend {

    private static final Logger LOG = Logger.getLogger(DockerDaemonBackend.class);

    private final DockerClient dockerClient;

    private volatile String operatingSystem;

    @Inject
    public DockerDaemonBackend(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /** The daemon's operating system in lower case, empty when the daemon could not be asked. */
    public String operatingSystem() {
        String known = operatingSystem;
        if (known != null) {
            return known;
        }
        try {
            String os = dockerClient.infoCmd().exec().getOperatingSystem();
            known = os == null ? "" : os.toLowerCase(Locale.ROOT);
            operatingSystem = known;
            return known;
        } catch (Exception e) {
            LOG.debugv("Could not read the Docker daemon's operating system: {0}", e.getMessage());
            return "";
        }
    }

    /** True when the daemon is Docker Desktop's; false on any other daemon or an unread answer. */
    public boolean isDockerDesktop() {
        return operatingSystem().contains("docker desktop");
    }
}
