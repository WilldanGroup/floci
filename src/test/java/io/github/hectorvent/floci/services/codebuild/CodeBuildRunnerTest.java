package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeBuildRunnerTest {

    @Test
    void endpointUrlUsesListenPortNotFlociPort() {
        // QUARKUS_HTTP_PORT=4811 with floci.port left at 4566: the server listens on 4811.
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.tls()).thenReturn(mock(EmulatorConfig.TlsConfig.class));
        when(config.port()).thenReturn(4566);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerReachableEndpoint endpoint = new ContainerReachableEndpoint(config, null, null, 4811);
        CodeBuildRunner runner = new CodeBuildRunner(null, null, null, null, null, null,
                config, containerDetector, null, endpoint, null, null);

        assertEquals("http://host.docker.internal:4811", runner.resolveEndpointUrl());
    }
}
