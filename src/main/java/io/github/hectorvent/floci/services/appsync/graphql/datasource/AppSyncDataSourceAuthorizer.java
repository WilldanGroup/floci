package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnShapes;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Checks the data source role before a resolver accesses a backing AWS resource. */
@ApplicationScoped
public class AppSyncDataSourceAuthorizer {

    private final IamService iamService;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final IamPolicyEvaluator policyEvaluator;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;

    @Inject
    public AppSyncDataSourceAuthorizer(IamService iamService,
                                       AssumeRolePolicyEvaluator trustEvaluator,
                                       IamPolicyEvaluator policyEvaluator,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper,
                                       EmulatorConfig config) {
        this.iamService = iamService;
        this.trustEvaluator = trustEvaluator;
        this.policyEvaluator = policyEvaluator;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public void authorize(DataSource dataSource, Object request, String region) {
        if (dataSource.getType() == DataSourceType.NONE
                || !config.services().iam().enforcementEnabled()) {
            return;
        }
        String roleArn = dataSource.getServiceRoleArn();
        if (roleArn == null || roleArn.isBlank()) {
            throw denied(dataSource, "no serviceRoleArn is configured");
        }
        AwsArnUtils.Arn roleId;
        try {
            roleId = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            throw denied(dataSource, "serviceRoleArn is not an IAM role ARN");
        }
        if (!AwsArnShapes.isIamRole(roleId)
                || roleId.resource().endsWith("/")) {
            throw denied(dataSource, "serviceRoleArn is not an IAM role ARN");
        }
        AwsArnUtils.Arn sourceId;
        try {
            sourceId = AwsArnUtils.parse(dataSource.getDataSourceArn());
        } catch (IllegalArgumentException e) {
            throw denied(dataSource, "data source ARN is invalid");
        }
        if (!"appsync".equals(sourceId.service()) || !sourceId.partition().equals(roleId.partition())
                || !sourceId.accountId().equals(roleId.accountId())) {
            throw denied(dataSource, "service role belongs to a different account or partition");
        }
        int suffix = sourceId.resource().indexOf("/datasources/");
        if (!sourceId.resource().startsWith("apis/") || suffix < 0) {
            throw denied(dataSource, "data source ARN is invalid");
        }
        String sourceArn = dataSource.getDataSourceArn().substring(0,
                dataSource.getDataSourceArn().indexOf("/datasources/"));
        String sourceAccount = sourceId.accountId();
        String roleName = roleId.resource().substring(roleId.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iamService.findRole(roleId.accountId(), roleName);
        if (role.isEmpty()) {
            return;
        }
        if (!roleArn.equals(role.get().getArn())) {
            throw denied(dataSource, "service role does not exist");
        }
        String servicePrincipal = ServicePrincipals.of("appsync");
        if (!trustEvaluator.allowsService(role.get().getAssumeRolePolicyDocument(),
                servicePrincipal, sourceArn, sourceAccount)) {
            throw denied(dataSource, "service role does not trust " + servicePrincipal);
        }

        List<Permission> permissions = permissions(dataSource, request, region, roleId);
        if (permissions.isEmpty()) {
            // Let the invoker report a malformed/unsupported operation or missing configuration.
            return;
        }
        CallerContext caller = iamService.resolvePrincipalContext(roleArn);
        Map<String, List<String>> conditionContext = region == null
                ? Map.of() : Map.of("aws:RequestedRegion", List.of(region));
        for (Permission permission : permissions) {
            if (policyEvaluator.simulatePrincipalPolicy(caller, permission.action(),
                    permission.resource(), conditionContext) != IamPolicyEvaluator.SimulationDecision.ALLOWED) {
                throw denied(dataSource, "service role cannot perform " + permission.action()
                        + " on " + permission.resource());
            }
        }
    }

    private List<Permission> permissions(DataSource dataSource, Object request, String region,
                                         AwsArnUtils.Arn roleId) {
        return switch (dataSource.getType()) {
            case AMAZON_DYNAMODB -> dynamoDbPermissions(dataSource, request, region, roleId);
            case AWS_LAMBDA -> lambdaPermissions(dataSource);
            case RELATIONAL_DATABASE -> relationalPermissions(dataSource, region);
            default -> throw denied(dataSource, "data source type is not supported for role authorization");
        };
    }

    /**
     * The actions DynamoDB checks for the request, each on its table's ARN. A batch or a transaction
     * names its own tables, and is checked as DynamoDB checks the call AppSync makes of it: a batch
     * read as {@code dynamodb:BatchGetItem} and a batch write as {@code dynamodb:BatchWriteItem} on
     * each table it names, and a transaction item by item, a get as {@code dynamodb:GetItem} and a
     * condition check as {@code dynamodb:ConditionCheckItem} (Service Authorization Reference,
     * Amazon DynamoDB; DynamoDB Developer Guide, Using IAM with DynamoDB transactions).
     */
    private List<Permission> dynamoDbPermissions(DataSource dataSource, Object request, String region,
                                                  AwsArnUtils.Arn roleId) {
        JsonNode normalized = objectMapper.valueToTree(request == null ? Map.of() : request);
        String operation = normalized.path("operation").asText(null);
        if (operation == null) {
            return List.of();
        }
        switch (operation) {
            case "BatchGetItem" -> {
                return tablePermissions("dynamodb:BatchGetItem", normalized.path("tables"), region, roleId);
            }
            case "BatchPutItem", "BatchDeleteItem" -> {
                return tablePermissions("dynamodb:BatchWriteItem", normalized.path("tables"), region, roleId);
            }
            case "TransactGetItems", "TransactWriteItems" -> {
                return transactionPermissions(normalized.path("transactItems"), region, roleId);
            }
            default -> {
                // A single-item operation, on the data source's own table.
            }
        }
        String table = text(dataSource.getDynamodbConfig(), "tableName");
        if (table == null) {
            return List.of();
        }
        String action = switch (operation) {
            case "GetItem", "PutItem", "UpdateItem", "DeleteItem", "Query", "Scan" ->
                    "dynamodb:" + operation;
            default -> throw denied(dataSource, "DynamoDB operation is not supported for role authorization");
        };
        String resource = tableArn(table, region, roleId);
        String index = normalized.path("index").asText(null);
        if (index != null && !index.isBlank()
                && ("dynamodb:Query".equals(action) || "dynamodb:Scan".equals(action))) {
            resource += "/index/" + index;
        }
        return List.of(new Permission(action, resource));
    }

    private List<Permission> tablePermissions(String action, JsonNode tables, String region,
                                              AwsArnUtils.Arn roleId) {
        List<Permission> permissions = new ArrayList<>();
        tables.fieldNames().forEachRemaining(table ->
                permissions.add(new Permission(action, tableArn(table, region, roleId))));
        return permissions;
    }

    private List<Permission> transactionPermissions(JsonNode transactItems, String region,
                                                    AwsArnUtils.Arn roleId) {
        Set<Permission> permissions = new LinkedHashSet<>();
        for (JsonNode item : transactItems) {
            String table = item.path("table").asText("");
            // A transaction's read names no operation; a write item's that is none of these four
            // is the invoker's to refuse.
            String action = switch (item.path("operation").asText("GetItem")) {
                case "GetItem" -> "dynamodb:GetItem";
                case "PutItem" -> "dynamodb:PutItem";
                case "UpdateItem" -> "dynamodb:UpdateItem";
                case "DeleteItem" -> "dynamodb:DeleteItem";
                case "ConditionCheck" -> "dynamodb:ConditionCheckItem";
                default -> null;
            };
            if (!table.isBlank() && action != null) {
                permissions.add(new Permission(action, tableArn(table, region, roleId)));
            }
        }
        return new ArrayList<>(permissions);
    }

    private static String tableArn(String table, String region, AwsArnUtils.Arn roleId) {
        return new AwsArnUtils.Arn(roleId.partition(), "dynamodb", region,
                roleId.accountId(), "table/" + table).toString();
    }

    private List<Permission> lambdaPermissions(DataSource dataSource) {
        String functionArn = text(dataSource.getLambdaConfig(), "lambdaFunctionArn");
        return functionArn == null ? List.of() : List.of(new Permission("lambda:InvokeFunction", functionArn));
    }

    private List<Permission> relationalPermissions(DataSource dataSource, String region) {
        Map<String, Object> config = dataSource.getRelationalDatabaseConfig();
        Object rawEndpoint = config == null ? null : config.get("rdsHttpEndpointConfig");
        if (!(rawEndpoint instanceof Map<?, ?> endpoint)) {
            return List.of();
        }
        String cluster = text(endpoint, "dbClusterIdentifier");
        String secret = text(endpoint, "awsSecretStoreArn");
        if (cluster == null || secret == null) {
            return List.of();
        }
        String endpointRegion = text(endpoint, "awsRegion");
        String clusterArn = AwsArnUtils.looksLikeArn(cluster) ? cluster
                : AwsArnUtils.Arn.of("rds", endpointRegion == null ? region : endpointRegion,
                        regionResolver.getAccountId(), "cluster:" + cluster).toString();
        List<Permission> permissions = new ArrayList<>();
        permissions.add(new Permission("rds-data:ExecuteStatement", clusterArn));
        permissions.add(new Permission("secretsmanager:GetSecretValue", secret));
        return permissions;
    }

    private static String text(Map<?, ?> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static AwsException denied(DataSource dataSource, String reason) {
        return new AwsException("AccessDeniedException",
                "AppSync data source " + dataSource.getName() + ": " + reason, 403);
    }

    private record Permission(String action, String resource) {}
}
