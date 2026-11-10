package io.github.hectorvent.floci.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A build's variables as its commands read them. A {@code PARAMETER_STORE} variable's value names a
 * parameter, and a {@code SECRETS_MANAGER} variable's a secret, "substituted for the name at runtime"
 * (CodeBuild API Reference, EnvironmentVariable), whether the project, the build's start or the
 * buildspec's {@code env.parameter-store} and {@code env.secrets-manager} declare it. Each is read as the
 * project's service role, which needs {@code ssm:GetParameters} for a parameter (Build specification
 * reference, env/parameter-store), and {@code secretsmanager:GetSecretValue} for a secret, with IAM
 * enforcement on. One the role may not read, or that does not stand, fails the build in
 * {@code DOWNLOAD_SOURCE} before its buildspec runs ("Troubleshooting AWS CodeBuild", Builds might fail
 * when getting parameters from Amazon EC2 Parameter Store), with the service's code and words.
 */
@ApplicationScoped
public class CodeBuildVariables {

    static final String PARAMETER_STORE = "PARAMETER_STORE";
    static final String SECRETS_MANAGER = "SECRETS_MANAGER";
    private static final String GET_PARAMETERS = "ssm:GetParameters";
    private static final String GET_SECRET_VALUE = "secretsmanager:GetSecretValue";
    /** A secret's ARN has seven colon-separated fields, {@code arn:partition:secretsmanager:region:account:secret:name}. */
    private static final int SECRET_ARN_FIELDS = 7;

    private final SsmService ssmService;
    private final SecretsManagerService secretsManagerService;
    private final IamService iamService;
    private final IamPolicyEvaluator policyEvaluator;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;

    @Inject
    public CodeBuildVariables(SsmService ssmService, SecretsManagerService secretsManagerService, IamService iamService,
                              IamPolicyEvaluator policyEvaluator, EmulatorConfig config, ObjectMapper objectMapper) {
        this.ssmService = ssmService;
        this.secretsManagerService = secretsManagerService;
        this.iamService = iamService;
        this.policyEvaluator = policyEvaluator;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** A variable as it was declared: its name, its type, and its value, which a lookup's type names. */
    record Variable(String name, String type, String value) {
    }

    /** Who reads a build's variables: its project's role, by its session, in the project's account and Region. */
    record Reader(String roleArn, String sessionArn, String accountId, String region) {
    }

    /**
     * Each variable's value as the build reads it, in the order given, a later one taking an earlier one's
     * name.
     *
     * @throws Refused for the first variable the role may not read or whose parameter or secret does not
     *         stand; no later one is read
     */
    Map<String, String> resolve(List<Variable> variables, Reader reader) throws Refused {
        Map<String, Variable> effective = new LinkedHashMap<>();
        for (Variable variable : variables) {
            effective.remove(variable.name());
            effective.put(variable.name(), variable);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Variable variable : effective.values()) {
            String value = variable.value() == null ? "" : variable.value();
            if (PARAMETER_STORE.equals(variable.type())) {
                value = parameter(variable.name(), value, reader);
            } else if (SECRETS_MANAGER.equals(variable.type())) {
                value = secret(variable.name(), value, reader);
            }
            values.put(variable.name(), value);
        }
        return values;
    }

    private String parameter(String variable, String name, Reader reader) throws Refused {
        String resource = AwsArnUtils.looksLikeArn(name) ? name
                : AwsArnUtils.Arn.of("ssm", reader.region(), reader.accountId(),
                        "parameter/" + (name.startsWith("/") ? name.substring(1) : name)).toString();
        authorize(variable, GET_PARAMETERS, resource, reader);
        try {
            return RequestScopes.callAs(reader.accountId(), reader.region(),
                    () -> ssmService.getParameter(name, true, reader.region())).getValue();
        } catch (AwsException e) {
            throw new Refused(variable, e.getErrorCode(), e.getMessage());
        }
    }

    /**
     * A secret by its reference, {@code secret-id:json-key:version-stage:version-id}, every part but the
     * secret's name or ARN optional (Build specification reference, env/secrets-manager): the secret's whole
     * text, or the one key of its JSON text the reference names.
     */
    private String secret(String variable, String reference, Reader reader) throws Refused {
        String[] fields = reference.split(":", -1);
        int idFields = AwsArnUtils.looksLikeArn(reference) ? Math.min(SECRET_ARN_FIELDS, fields.length) : 1;
        String secretId = String.join(":", Arrays.copyOfRange(fields, 0, idFields));
        String jsonKey = field(fields, idFields);
        String versionStage = field(fields, idFields + 1);
        String versionId = field(fields, idFields + 2);

        Secret secret;
        try {
            secret = RequestScopes.callAs(reader.accountId(), reader.region(),
                    () -> secretsManagerService.describeSecret(secretId, reader.region()));
        } catch (AwsException e) {
            throw new Refused(variable, e.getErrorCode(), e.getMessage());
        }
        authorize(variable, GET_SECRET_VALUE, secret.getArn(), reader);
        SecretVersion version;
        try {
            version = RequestScopes.callAs(reader.accountId(), reader.region(),
                    () -> secretsManagerService.getSecretValue(secretId, versionId, versionStage, reader.region()));
        } catch (AwsException e) {
            throw new Refused(variable, e.getErrorCode(), e.getMessage());
        }
        String text = version.getSecretString() != null ? version.getSecretString() : "";
        if (jsonKey == null) {
            return text;
        }
        JsonNode value;
        try {
            value = objectMapper.readTree(text).get(jsonKey);
        } catch (Exception e) {
            throw new Refused(variable, null, "the secret " + secretId + " is not JSON, so it has no key " + jsonKey);
        }
        if (value == null || value.isNull()) {
            throw new Refused(variable, null, "the secret " + secretId + " has no key " + jsonKey);
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static String field(String[] fields, int index) {
        return index < fields.length && !fields[index].isEmpty() ? fields[index] : null;
    }

    /** Refuses what the role's policies do not allow it, with IAM enforcement on, in IAM's words. */
    private void authorize(String variable, String action, String resource, Reader reader) throws Refused {
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        CallerContext caller;
        try {
            caller = iamService.resolvePrincipalContext(reader.roleArn());
        } catch (AwsException e) {
            throw new Refused(variable, e.getErrorCode(), e.getMessage());
        }
        String refusal = AccessDeniedMessages.firstRefusal(policyEvaluator, caller, null, action, resource, Map.of());
        if (refusal != null) {
            throw new Refused(variable, "AccessDeniedException",
                    AccessDeniedMessages.notAuthorized(reader.sessionArn(), action, resource, refusal));
        }
    }

    /** A variable the build cannot be handed: the service's code, where it gave one, and its words. */
    public static final class Refused extends Exception {

        Refused(String variable, String code, String words) {
            super("Variable " + variable + ": " + (code == null ? "" : code + ": ") + words);
        }
    }
}
