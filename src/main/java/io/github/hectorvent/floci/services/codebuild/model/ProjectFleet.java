package io.github.hectorvent.floci.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The compute fleet a project's environment builds on: the CodeBuild API Reference's {@code ProjectFleet}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectFleet {
    public ProjectFleet() {}

    private String fleetArn;

    public String getFleetArn() { return fleetArn; }
    public void setFleetArn(String fleetArn) { this.fleetArn = fleetArn; }
}
