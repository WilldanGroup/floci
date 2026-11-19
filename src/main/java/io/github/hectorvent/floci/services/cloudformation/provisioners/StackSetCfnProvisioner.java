package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationService;
import io.github.hectorvent.floci.services.cloudformation.StackSetService;
import io.github.hectorvent.floci.services.cloudformation.model.StackInstance;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackSet;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperation;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperationPreferences;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Provisions {@code AWS::CloudFormation::StackSet} through Floci's own StackSets
 * ({@link StackSetService}), as the CloudFormation Template Reference's page for the type has it: the
 * StackSet is created from {@code TemplateBody} or {@code TemplateURL}, never both, with its
 * {@code Parameters}, {@code Capabilities}, {@code Tags}, {@code Description}, {@code PermissionModel},
 * {@code AutoDeployment} and {@code ManagedExecution}, and each {@code StackInstancesGroup}'s instances are
 * created in its accounts, or organizational units, and Regions under the {@code OperationPreferences}. {@code Ref}
 * and {@code Fn::GetAtt StackSetId} return the StackSet's id.
 *
 * <p>An operation that fails past its failure tolerance fails the resource, and with it the stack, in the words
 * CloudFormation's handler uses: "Stack set operation [<id>] was unexpectedly stopped or failed. status reason(s):
 * [<each failed instance's reason>]", read on a real account (us-west-2, 2026-11-19), where the handler reported it
 * as a resource that "did not stabilize" ({@code HandlerErrorCode: NotStabilized}). What a failed create made is
 * deleted before the resource fails, as the stack's rollback would delete it. An operation within its tolerance
 * passes, and the failed instance stays {@code OUTDATED} with its reason, so a Region AWS refuses leaves the stack
 * standing.
 *
 * <p>{@code StackSetName} and {@code PermissionModel} are create-only, so a change to either creates a new StackSet
 * and deletes the old one once the stack has finished updating. Every other property updates in place: the
 * instances a group no longer names are deleted, the StackSet is updated and its instances with it, and the instances
 * a group newly names are created. An update in place that a later resource fails is put back from the StackSet as it
 * stood before it. Deleting the resource deletes every instance, with its stack, and then the StackSet.
 *
 * <p>{@code AdministrationRoleARN}, {@code ExecutionRoleName} and {@code CallAs} are read and not used: Floci's
 * StackSets operate an instance as the account it stands in, under no role, as they do for {@code CreateStackSet}.
 * {@code ParameterOverrides} on a group is refused, since Floci's StackSets hold no per-instance values.
 */
@ApplicationScoped
public class StackSetCfnProvisioner implements CfnResourceProvisioner {

    static final String TYPE = "AWS::CloudFormation::StackSet";
    /** The StackSet as the last provision left it, which an update compares with and a rollback restores. */
    static final String SPEC_ATTR = "__FlociStackSetSpec";
    /** The StackSet as it stood before an update in place, kept until the update commits or rolls back. */
    static final String UPDATE_SNAPSHOT_ATTR = "__FlociStackSetUpdateSnapshot";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StackSetService stackSets;
    private final CloudFormationService cloudFormation;

    @Inject
    public StackSetCfnProvisioner(StackSetService stackSets, CloudFormationService cloudFormation) {
        this.stackSets = stackSets;
        this.cloudFormation = cloudFormation;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        JsonNode resolved = props == null ? null : ctx.engine().resolveNodeOmittingNoValue(props);
        ObjectNode spec = specOf(resolved == null || resolved.isMissingNode() ? MAPPER.createObjectNode() : resolved, r);
        String name = spec.path("name").asText();
        String permissionModel = spec.path("permissionModel").asText();
        String priorId = ctx.priorPhysicalId();
        // The StackSet this resource made, unless a create-only property changed: then a new one is created, and
        // the old one deleted once the stack update commits.
        StackSet standing = priorId == null ? null : stackSets.listStackSets().stream()
                .filter(ss -> priorId.equals(ss.getStackSetId()) && name.equals(ss.getStackSetName())
                        && permissionModel.equals(ss.getPermissionModel()))
                .findFirst().orElse(null);
        if (standing != null && ctx.isUpdate()) {
            ObjectNode prior = readSpec(attributesBefore.get(SPEC_ATTR));
            // Kept before anything changes, so an update that fails partway is still put back.
            r.getAttributes().put(UPDATE_SNAPSHOT_ATTR, attributesBefore.getOrDefault(SPEC_ATTR, ""));
            apply(name, prior, spec);
            r.setPhysicalId(priorId);
        } else {
            r.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
            r.setPhysicalId(create(spec));
        }
        r.getAttributes().put("StackSetId", r.getPhysicalId());
        r.getAttributes().put(SPEC_ATTR, spec.toString());
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /** Deletes every instance of the StackSet, with its stack, and then the StackSet; one already gone is done. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        String name = nameOf(physicalId);
        boolean standing = name != null && stackSets.listStackSets().stream()
                .anyMatch(ss -> physicalId.equals(ss.getStackSetId()));
        if (!standing) {
            return;
        }
        deleteInstances(name, stackSets.listStackInstances(name, null, null), StackSetOperationPreferences.DEFAULT);
        stackSets.deleteStackSet(name);
    }

    /** The id is the StackSet's name, a colon and a UUID; the name alone names no parent. */
    @Override
    public boolean idPrefixCanNameParent(String resourceType) {
        return false;
    }

    /**
     * An update in place that failed after its snapshot may already have changed the StackSet, so the engine keeps
     * this attempt for {@link #rollbackUpdate} instead of putting the previous resource back over it.
     */
    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(UPDATE_SNAPSHOT_ATTR);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    /** The update committed, so its snapshot is dropped; a replaced StackSet is deleted. */
    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record, and an update in place by applying the StackSet as it
     * stood before it, which CloudFormation's rollback does by running the update handler with the previous
     * properties. The snapshot is spent only once that succeeded, so a rollback that throws can be retried.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return false;
        }
        ObjectNode previous = readSpec(raw);
        if (previous == null) {
            return false;
        }
        apply(previous.path("name").asText(), readSpec(resource.getAttributes().get(SPEC_ATTR)), previous);
        resource.getAttributes().put(SPEC_ATTR, previous.toString());
        resource.getAttributes().remove(UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    // ── Create and update ─────────────────────────────────────────────────

    /** Creates the StackSet and each group's instances, deleting what it made if an operation fails. */
    private String create(ObjectNode spec) {
        String name = spec.path("name").asText();
        StackSet created = stackSets.createStackSet(name, spec.path("template").asText(), stringMap(spec.path("parameters")),
                strings(spec.path("capabilities")), stringMap(spec.path("tags")), text(spec, "description"),
                spec.path("permissionModel").asText(), spec.path("autoDeployment").asBoolean(false),
                spec.path("retainStacksOnAccountRemoval").asBoolean(false),
                spec.path("managedExecution").asBoolean(false));
        try {
            StackSetOperationPreferences preferences = preferencesOf(spec);
            for (Target target : targetsOf(spec)) {
                requireSucceeded(name, stackSets.createStackInstances(name, target.accounts(), target.regions(),
                        target.organizationalUnits(), null, preferences));
            }
        } catch (RuntimeException e) {
            delete(TYPE, created.getStackSetId(), null);
            throw e;
        }
        return created.getStackSetId();
    }

    /**
     * Takes the StackSet from {@code prior} to {@code desired}: the instances no group still names are deleted, the
     * StackSet is updated where any of its own properties changed, which updates its instances, and the instances a
     * group newly names are created, each operation under the desired preferences. With no {@code prior} the StackSet
     * is updated whatever it holds.
     */
    private void apply(String name, ObjectNode prior, ObjectNode desired) {
        StackSetOperationPreferences preferences = preferencesOf(desired);
        Set<String> wanted = new LinkedHashSet<>();
        for (Target target : targetsOf(desired)) {
            wanted.addAll(target.keys());
        }
        List<StackInstance> standing = stackSets.listStackInstances(name, null, null);
        List<StackInstance> unwanted = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (StackInstance instance : standing) {
            List<String> keys = keysOf(instance);
            present.addAll(keys);
            if (keys.stream().noneMatch(wanted::contains)) {
                unwanted.add(instance);
            }
        }
        deleteInstances(name, unwanted, preferences);

        if (prior == null || !setLevel(prior).equals(setLevel(desired))) {
            requireSucceeded(name, stackSets.updateStackSet(name, desired.path("template").asText(),
                    stringMap(desired.path("parameters")), strings(desired.path("capabilities")),
                    stringMap(desired.path("tags")), text(desired, "description"), null,
                    "SERVICE_MANAGED".equals(desired.path("permissionModel").asText())
                            ? desired.path("autoDeployment").asBoolean(false) : null,
                    desired.path("retainStacksOnAccountRemoval").asBoolean(false),
                    desired.path("managedExecution").asBoolean(false), preferences));
        }

        for (Target target : targetsOf(desired)) {
            Target missing = target.without(present);
            if (missing != null) {
                requireSucceeded(name, stackSets.createStackInstances(name, missing.accounts(), missing.regions(),
                        missing.organizationalUnits(), null, preferences));
            }
        }
    }

    /** Deletes instances, by account or by organizational unit, a Region set at a time. */
    private void deleteInstances(String name, List<StackInstance> instances, StackSetOperationPreferences preferences) {
        Map<String, Set<String>> regionsByAccount = new TreeMap<>();
        Map<String, Set<String>> regionsByUnit = new TreeMap<>();
        for (StackInstance instance : instances) {
            List<String> units = instance.getDeploymentTargetIds();
            if (units == null || units.isEmpty()) {
                regionsByAccount.computeIfAbsent(instance.getAccount(), a -> new LinkedHashSet<>())
                        .add(instance.getRegion());
            } else {
                for (String unit : units) {
                    regionsByUnit.computeIfAbsent(unit, u -> new LinkedHashSet<>()).add(instance.getRegion());
                }
            }
        }
        regionsByAccount.forEach((account, regions) -> requireSucceeded(name, stackSets.deleteStackInstances(name,
                List.of(account), List.copyOf(regions), List.of(), false, preferences)));
        regionsByUnit.forEach((unit, regions) -> requireSucceeded(name, stackSets.deleteStackInstances(name,
                List.of(), List.copyOf(regions), List.of(unit), false, preferences)));
    }

    /** Fails the resource on an operation past its tolerance, naming each failed instance's reason. */
    private void requireSucceeded(String name, StackSetOperation operation) {
        if (!"FAILED".equals(operation.getStatus())) {
            return;
        }
        List<String> reasons = stackSets.listStackInstances(name, null, null).stream()
                .filter(instance -> "FAILED".equals(instance.getDetailedStatus()))
                .map(StackInstance::getStatusReason)
                .filter(Objects::nonNull)
                .toList();
        throw new AwsException("NotStabilized", "Stack set operation [" + operation.getOperationId()
                + "] was unexpectedly stopped or failed. status reason(s): " + reasons, 400);
    }

    // ── The resolved properties ───────────────────────────────────────────

    /**
     * The resolved properties as one JSON object, the shape the resource is compared and restored by. Refuses what
     * the Template Reference refuses: no {@code StackSetName} or {@code PermissionModel}, both {@code TemplateBody}
     * and {@code TemplateURL} or neither.
     */
    private ObjectNode specOf(JsonNode p, StackResource r) {
        String name = requiredText(p, "StackSetName", r);
        String permissionModel = requiredText(p, "PermissionModel", r);
        String body = optionalText(p, "TemplateBody");
        String url = optionalText(p, "TemplateURL");
        if ((body == null) == (url == null)) {
            throw new AwsException("ValidationError", TYPE + " " + r.getLogicalId()
                    + ": you must include either TemplateURL or TemplateBody in a StackSet, but you can't use both.",
                    400);
        }
        String template = cloudFormation.resolveTemplateBody(body, url);
        StackSetService.requireTemplateSize(body, url, template);

        ObjectNode spec = MAPPER.createObjectNode();
        spec.put("name", name);
        spec.put("permissionModel", permissionModel);
        spec.put("template", template);
        ObjectNode parameters = spec.putObject("parameters");
        for (JsonNode parameter : array(p.path("Parameters"))) {
            parameters.put(parameter.path("ParameterKey").asText(), parameter.path("ParameterValue").asText(""));
        }
        ArrayNode capabilities = spec.putArray("capabilities");
        listOf(p.path("Capabilities")).forEach(capabilities::add);
        ObjectNode tags = spec.putObject("tags");
        for (JsonNode tag : array(p.path("Tags"))) {
            tags.put(tag.path("Key").asText(), tag.path("Value").asText(""));
        }
        String description = optionalText(p, "Description");
        if (description != null) {
            spec.put("description", description);
        }
        spec.put("autoDeployment", p.path("AutoDeployment").path("Enabled").asBoolean(false));
        spec.put("retainStacksOnAccountRemoval",
                p.path("AutoDeployment").path("RetainStacksOnAccountRemoval").asBoolean(false));
        spec.put("managedExecution", p.path("ManagedExecution").path("Active").asBoolean(false));
        JsonNode preferences = p.path("OperationPreferences");
        if (preferences.has("FailureToleranceCount")) {
            spec.put("failureToleranceCount", integer(preferences.get("FailureToleranceCount"), r));
        }
        if (preferences.has("FailureTolerancePercentage")) {
            spec.put("failureTolerancePercentage", integer(preferences.get("FailureTolerancePercentage"), r));
        }
        ArrayNode groups = spec.putArray("groups");
        for (JsonNode group : array(p.path("StackInstancesGroup"))) {
            if (!array(group.path("ParameterOverrides")).isEmpty()) {
                throw new AwsException("ValidationError", TYPE + " " + r.getLogicalId()
                        + ": ParameterOverrides on a StackInstancesGroup is not supported by Floci.", 400);
            }
            ObjectNode entry = groups.addObject();
            ArrayNode accounts = entry.putArray("accounts");
            listOf(group.path("DeploymentTargets").path("Accounts")).forEach(accounts::add);
            ArrayNode units = entry.putArray("organizationalUnits");
            listOf(group.path("DeploymentTargets").path("OrganizationalUnitIds")).forEach(units::add);
            ArrayNode regions = entry.putArray("regions");
            listOf(group.path("Regions")).forEach(regions::add);
        }
        return spec;
    }

    /** What an UpdateStackSet changes: everything but the name, the permission model and the instances. */
    private static ObjectNode setLevel(ObjectNode spec) {
        ObjectNode level = spec.deepCopy();
        level.remove(List.of("name", "permissionModel", "groups", "failureToleranceCount", "failureTolerancePercentage"));
        return level;
    }

    private static StackSetOperationPreferences preferencesOf(ObjectNode spec) {
        return new StackSetOperationPreferences(
                spec.has("failureToleranceCount") ? spec.get("failureToleranceCount").asInt() : null,
                spec.has("failureTolerancePercentage") ? spec.get("failureTolerancePercentage").asInt() : null);
    }

    /** The groups' targets, each group's accounts or organizational units in its Regions; empty ones dropped. */
    private static List<Target> targetsOf(ObjectNode spec) {
        List<Target> targets = new ArrayList<>();
        for (JsonNode group : spec.path("groups")) {
            Target target = new Target(strings(group.path("accounts")), strings(group.path("organizationalUnits")),
                    strings(group.path("regions")));
            if (!target.regions().isEmpty()
                    && !(target.accounts().isEmpty() && target.organizationalUnits().isEmpty())) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** The keys an instance stands for: its account's, or each organizational unit's it was deployed for. */
    private static List<String> keysOf(StackInstance instance) {
        List<String> units = instance.getDeploymentTargetIds();
        if (units == null || units.isEmpty()) {
            return List.of(key(instance.getAccount(), instance.getRegion()));
        }
        return units.stream().map(unit -> key(unit, instance.getRegion())).toList();
    }

    private static String key(String target, String region) {
        return target + "|" + region;
    }

    /** A group's accounts or organizational units in its Regions. */
    private record Target(List<String> accounts, List<String> organizationalUnits, List<String> regions) {

        Set<String> keys() {
            Set<String> keys = new LinkedHashSet<>();
            for (String target : accounts.isEmpty() ? organizationalUnits : accounts) {
                for (String region : regions) {
                    keys.add(key(target, region));
                }
            }
            return keys;
        }

        /**
         * The part of this target no instance stands for yet, as one operation's targets: the Regions where any of
         * its accounts or units lacks an instance. CreateStackInstances leaves alone an instance that stands.
         */
        Target without(Set<String> present) {
            List<String> regionsMissing = regions.stream()
                    .filter(region -> (accounts.isEmpty() ? organizationalUnits : accounts).stream()
                            .anyMatch(target -> !present.contains(key(target, region))))
                    .toList();
            return regionsMissing.isEmpty() ? null : new Target(accounts, organizationalUnits, regionsMissing);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static String nameOf(String physicalId) {
        if (physicalId == null) {
            return null;
        }
        int colon = physicalId.indexOf(':');
        return colon > 0 ? physicalId.substring(0, colon) : null;
    }

    private static ObjectNode readSpec(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node instanceof ObjectNode object ? object : null;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stack set resource's recorded properties", e);
        }
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    /** A resolved list: an array's non-blank members, or a comma-joined text's. */
    private static List<String> listOf(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
        } else {
            out.addAll(List.of(node.asText().split(",")));
        }
        out.replaceAll(String::trim);
        out.removeIf(String::isBlank);
        return out;
    }

    private static List<String> strings(JsonNode node) {
        return listOf(node);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.properties().forEach(entry -> out.put(entry.getKey(), entry.getValue().asText()));
        return out;
    }

    private static String text(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.get(name).asText() : null;
    }

    private static String optionalText(JsonNode p, String name) {
        JsonNode value = p.path(name);
        return value.isMissingNode() || value.isNull() || value.asText().isEmpty() ? null : value.asText();
    }

    private static String requiredText(JsonNode p, String name, StackResource r) {
        String value = optionalText(p, name);
        if (value == null) {
            throw new AwsException("ValidationError",
                    TYPE + " " + r.getLogicalId() + " is missing the required " + name + " property", 400);
        }
        return value;
    }

    private static int integer(JsonNode value, StackResource r) {
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", TYPE + " " + r.getLogicalId()
                    + ": OperationPreferences values must be integers.", 400);
        }
    }
}
