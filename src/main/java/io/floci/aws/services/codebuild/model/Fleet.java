package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A CodeBuild compute fleet, in the shape the CodeBuild API Reference's {@code Fleet} data type
 * gives it. Floci runs no reserved capacity, so a fleet is a record a project names; the nested
 * configurations are kept as given.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Fleet {
    public Fleet() {}

    private String arn;
    private String name;
    private String id;
    private Double created;
    private Double lastModified;
    private Map<String, String> status;
    private Integer baseCapacity;
    private String environmentType;
    private String computeType;
    private Map<String, Object> computeConfiguration;
    private Map<String, Object> scalingConfiguration;
    private String overflowBehavior;
    private Map<String, Object> vpcConfig;
    private Map<String, Object> proxyConfiguration;
    private String imageId;
    private String fleetServiceRole;
    private List<Map<String, String>> tags;

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Double getCreated() { return created; }
    public void setCreated(Double created) { this.created = created; }

    public Double getLastModified() { return lastModified; }
    public void setLastModified(Double lastModified) { this.lastModified = lastModified; }

    public Map<String, String> getStatus() { return status; }
    public void setStatus(Map<String, String> status) { this.status = status; }

    public Integer getBaseCapacity() { return baseCapacity; }
    public void setBaseCapacity(Integer baseCapacity) { this.baseCapacity = baseCapacity; }

    public String getEnvironmentType() { return environmentType; }
    public void setEnvironmentType(String environmentType) { this.environmentType = environmentType; }

    public String getComputeType() { return computeType; }
    public void setComputeType(String computeType) { this.computeType = computeType; }

    public Map<String, Object> getComputeConfiguration() { return computeConfiguration; }
    public void setComputeConfiguration(Map<String, Object> computeConfiguration) {
        this.computeConfiguration = computeConfiguration;
    }

    public Map<String, Object> getScalingConfiguration() { return scalingConfiguration; }
    public void setScalingConfiguration(Map<String, Object> scalingConfiguration) {
        this.scalingConfiguration = scalingConfiguration;
    }

    public String getOverflowBehavior() { return overflowBehavior; }
    public void setOverflowBehavior(String overflowBehavior) { this.overflowBehavior = overflowBehavior; }

    public Map<String, Object> getVpcConfig() { return vpcConfig; }
    public void setVpcConfig(Map<String, Object> vpcConfig) { this.vpcConfig = vpcConfig; }

    public Map<String, Object> getProxyConfiguration() { return proxyConfiguration; }
    public void setProxyConfiguration(Map<String, Object> proxyConfiguration) {
        this.proxyConfiguration = proxyConfiguration;
    }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getFleetServiceRole() { return fleetServiceRole; }
    public void setFleetServiceRole(String fleetServiceRole) { this.fleetServiceRole = fleetServiceRole; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }
}
