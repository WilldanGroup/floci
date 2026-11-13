# CodeBuild

Floci implements the CodeBuild API — stored-state management plus real build execution inside Docker containers.

**Protocol:** JSON 1.1 — `POST /` with `X-Amz-Target: CodeBuild_20161006.<Action>`

**ARN formats:**

- `arn:aws:codebuild:<region>:<account>:project/<name>`
- `arn:aws:codebuild:<region>:<account>:report-group/<name>`
- `arn:aws:codebuild:<region>:<account>:fleet/<name>:<uuid>`
- `arn:aws:codebuild:<region>:<account>:token/<type>-<uuid>`
- `arn:aws:codebuild:<region>:<account>:build/<project>:<uuid>`

## Supported Operations (25 total)

### Projects

| Operation | Notes |
|---|---|
| `CreateProject` | Stores project config; requires `name`, `source.type`, `artifacts.type`, `environment`, `serviceRole` |
| `UpdateProject` | Partial update — only supplied fields are modified |
| `DeleteProject` | Removes project by name |
| `BatchGetProjects` | Returns found projects and a `projectsNotFound` list |
| `ListProjects` | Returns project names in the region, with optional name, creation-time, or last-modified-time sorting |

A project's `environment.fleet.fleetArn` is stored and returned; a build on it runs as an on-demand build does.

### Compute Fleets

| Operation | Notes |
|---|---|
| `CreateFleet` | Stores the fleet; requires `name`, `baseCapacity`, `computeType`, `environmentType`; the fleet is `ACTIVE` at once and no reserved capacity runs |
| `UpdateFleet` | Partial update by ARN — only supplied fields are modified |
| `DeleteFleet` | Removes the fleet by ARN |
| `BatchGetFleets` | Returns found fleets by name or ARN and a `fleetsNotFound` list |
| `ListFleets` | Returns the account's fleet names in the region, with optional name, creation-time, or last-modified-time sorting |

### Build Execution

| Operation | Notes |
|---|---|
| `StartBuild` | Runs the build in CodeBuild's local agent on the project's image (see Build Execution Model); returns immediately with `IN_PROGRESS` status |
| `BatchGetBuilds` | Returns current build state; poll until `buildComplete` is `true` |
| `ListBuilds` | Returns all build IDs in the region, most recent first |
| `ListBuildsForProject` | Returns build IDs for a specific project |
| `StopBuild` | Signals a running build to stop; build transitions to `STOPPED` |
| `RetryBuild` | Starts a new build using the same config as a completed build; returns a new build record |

### Report Groups

| Operation | Notes |
|---|---|
| `CreateReportGroup` | Stores report group config |
| `UpdateReportGroup` | Partial update by ARN |
| `DeleteReportGroup` | Removes report group by ARN |
| `BatchGetReportGroups` | Returns found report groups and a `reportGroupsNotFound` list |
| `ListReportGroups` | Returns all report group ARNs in the region |

### Source Credentials

| Operation | Notes |
|---|---|
| `ImportSourceCredentials` | Stores server type and auth type; deduplicated by `serverType+authType`; token is accepted but not returned |
| `ListSourceCredentials` | Returns stored credential metadata (no tokens) |
| `DeleteSourceCredentials` | Removes source credentials by ARN |

### Images

| Operation | Notes |
|---|---|
| `ListCuratedEnvironmentImages` | Returns a static list of standard CodeBuild images for AL2 and Ubuntu |

## Build Execution Model

A build runs in CodeBuild's local agent, the image AWS publishes for running a build as CodeBuild runs it ("Run builds locally with the AWS CodeBuild agent", AWS CodeBuild User Guide), so the agent's executor runs the buildspec's phases, under the shell `env.shell` names (`bash` or `/bin/sh`), and writes the build's log. Floci runs the agent of the Docker host's architecture, pinned by the digest AWS publishes for it:

| Architecture | Agent |
|---|---|
| x86_64 | `public.ecr.aws/codebuild/local-builds@sha256:ccb19bdd7af94e4dc761e4c58c267e9455c28ec68d938086b4dc1cf8fe6b0940` (`:latest`) |
| aarch64 | `public.ecr.aws/codebuild/local-builds@sha256:7d7b5d35d2ac4e062ae7ba8c662ffed15229a52d09bd0d664a7816c439679192` (`:aarch64`) |

Each `StartBuild` call:

1. Refuses, in its `PROVISIONING` phase and before any agent starts, a project whose environment type is the other architecture's (`ARM_CONTAINER` and the other `ARM_*` types are aarch64, `LINUX_CONTAINER` and the other `LINUX_*` types x86_64), naming both: CodeBuild runs a build on a host of its environment's architecture, and Floci runs nothing emulated
2. Makes the build a working folder, holding its source (an `S3` source's archive, extracted; nothing for `NO_SOURCE`) and its buildspec
3. Starts the agent with what `codebuild_build.sh` hands it: the Docker socket, `IMAGE_NAME` (the project's image), `SOURCE`, `ARTIFACTS` (a volume of the build's compose project, never a folder of the Docker host: the build's container runs as root, so a host folder it writes into could hold files the host's user cannot remove), `BUILDSPEC`, `INITIATOR`, `LOCAL_AGENT_IMAGE_NAME` and, for a privileged project, `DOCKER_PRIVILEGED_MODE`. The agent brings up two containers with docker-compose, itself and a container of the project's image the buildspec runs in, under a compose project of the build's own
4. Reads the agent's output for the build's phases (`DOWNLOAD_SOURCE` to `UPLOAD_ARTIFACTS`, each with the context it ended with) and sends the build's log to CloudWatch Logs under `/aws/codebuild/<project>`
5. Uploads the artifacts the agent collected to S3 if `artifacts.type=S3`: the archive, read out of the build's stopped container through Docker's archive API, or each file in it for `packaging: NONE`
6. Removes the agent, the containers and volumes it started and the working folder, then marks the build complete with `SUCCEEDED`, `FAILED`, `FAULT`, `TIMED_OUT` or `STOPPED`

Floci adds a compose file of its own after the agent's. With it the agent and the build's container join the Docker network builds run on, `FLOCI_SERVICES_CODEBUILD_DOCKER_NETWORK`, else `FLOCI_SERVICES_DOCKER_NETWORK`, else Floci's own when Floci runs in a container, and never Docker's default bridge: where none of those is a user-defined network, compose gives the build a network of its own. Every container a build starts, the agent Floci starts and the agent, build and credentials relay its docker-compose starts, and the compose project's three volumes, carry Floci's labels with the build's identity: `io.floci.service=codebuild` and `io.floci.resource-id=<build id>` (`<project>:<number>`), beside `floci_namespace` and `FLOCI_DOCKER_EXTRA_LABELS`, so `docker ps --filter label=io.floci.resource-id=<build id>` finds a build's containers while it runs. The build's container reads the buildspec Floci resolved (the request's override, the project's inline buildspec, or the source's `buildspec.yml`) at a path of its own, so a buildspec from the request never takes the place of a file of the source. The variables Floci hands the build, the project's and the request's among them, reach it as the agent hands a project's, so they take precedence over the buildspec's own `env.variables`, as CodeBuild orders them.

The working folder is made under `FLOCI_SERVICES_CODEBUILD_AGENT_WORKSPACE`, else `floci-codebuild` under `$SLUICE_TMP` where that is set, else `/tmp/floci-codebuild-<floci.port>`. The agent binds what is in it from the Docker host, each read-only, so that folder must be the same path on the host and in Floci's container: with Floci in a container, bind it at its own path (`-v /tmp/floci-codebuild-4566:/tmp/floci-codebuild-4566`). What Floci makes there it gives to the folder's owner, and no container of a build binds a host path it may write but the Docker socket, so nothing the build writes is left on the host.

## The Build's Credentials

A build runs as its project's `serviceRole`, as CodeBuild uses the service role for every operation it performs on the account's behalf. Floci mints a session of that role in the project's account, named `AWSCodeBuild-<build id after the project's name>`, so `aws sts get-caller-identity` inside the build answers `arn:aws:sts::<account>:assumed-role/<role name>/AWSCodeBuild-<id>`, and with `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` the role's policies decide what the build may call. Floci puts no access keys of its own in the build's environment.

How the session reaches the build depends on the Docker network the build joins, `FLOCI_SERVICES_CODEBUILD_DOCKER_NETWORK`, else `FLOCI_SERVICES_DOCKER_NETWORK`, else Floci's own when Floci runs in a container:

- **On a user-defined network**, the session is served the way CodeBuild serves it: the build gets `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` and none of `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `AWS_CONTAINER_CREDENTIALS_FULL_URI` or `AWS_CONTAINER_AUTHORIZATION_TOKEN`, and the AWS SDKs and CLI read the role's credentials at `http://169.254.170.2$AWS_CONTAINER_CREDENTIALS_RELATIVE_URI`, with no code change. The same credentials server ECS task roles use answers (see [ECS](ecs.md#task-iam-role-credentials)), reached through a relay Floci adds to the agent's compose project: a container of the network helper image in the build container's own network namespace, with `NET_ADMIN`, which holds `169.254.170.2` on that namespace's loopback and forwards to the server at Floci's address on the network. No packet to or from `169.254.0.0/16` crosses the Docker network, so a host whose firewall checks the reverse path of bridged traffic, as NixOS's does by default, does not drop it. The relay is healthy once it answers the build's own session, and the agent starts only then, so the build's first command finds its credentials. For that ordering the agent's own link from the build's container to it, `BMR`, is the same name as an alias on the network, as compose refuses the ordering with the link in place. Reads near expiry return a fresh session at the same path, and the path answers nothing once the build has ended.
- **On Docker's default bridge**, where the agent's containers cannot join Floci's network, the same session is handed as `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN`, so the build still runs as the role; these keys are not renewed during the build.

A build whose project's role does not exist in the project's account fails in its `PROVISIONING` phase, its context naming the role, and no agent starts. A variable of any of these names that the project, the buildspec or `StartBuild` sets still reaches the build.

Unlike CodeBuild, Floci does not evaluate the role's trust policy: a role that does not trust `codebuild.amazonaws.com` is still assumed.

## Build State Change Events

As a build starts and once it has ended, Floci puts a `CodeBuild Build State Change` event, source `aws.codebuild`, on the default event bus of the build's account and Region, as CodeBuild does ("Build notifications input format reference", AWS CodeBuild User Guide): `build-status` `IN_PROGRESS`, then `SUCCEEDED`, `FAILED`, `FAULT`, `TIMED_OUT` or `STOPPED`, with `build-id` (the build's ARN), `project-name`, `current-phase` and `additional-information`: the environment, the timeout, `build-complete`, the initiator, the start time, the source, the log group and stream, and the phases so far, times in CodeBuild's `Sep 1, 2017 4:12:29 PM` form. The event's `resources` hold the build's ARN. The end's event goes out once the build reads complete, so a rule's target that reads the build finds it ended. Not sent: `artifact` and the logs' `deep-link`, and `CodeBuild Build Phase Change` events.

## Parameter Store and Secrets Manager Variables

A variable of type `PARAMETER_STORE` names a parameter and one of type `SECRETS_MANAGER` a secret, and the build reads the value, not the name, as CodeBuild substitutes it at run time. That holds for a variable the project declares, one `StartBuild`'s `environmentVariablesOverride` declares, and the buildspec's `env.parameter-store` and `env.secrets-manager` entries; a secret is named as `secret-id:json-key:version-stage:version-id`, every part but the secret's name or ARN optional, and `json-key` reads one key of the secret's JSON. Floci reads each before the agent starts, as the project's service role: with `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` the role's policies must allow `ssm:GetParameters` on the parameter, or `secretsmanager:GetSecretValue` on the secret. One the role may not read, or whose parameter or secret does not exist, fails the build in its `DOWNLOAD_SOURCE` phase before any of its buildspec runs, with the status code `Decrypted Variables Error` the agent gives such a variable and a message naming the variable, then the service's code and words, for example `Variable TOKEN: AccessDeniedException: User: arn:aws:sts::000000000000:assumed-role/build-role/AWSCodeBuild-7 is not authorized to perform: ssm:GetParameters on resource: ...`.

## Buildspec Support

Floci parses the `buildspec.yml` embedded in the project or provided via `buildspecOverride`. Supported fields:

- `phases` — `install`, `pre_build`, `build`, `post_build` command lists
- `artifacts.files` — list of file patterns to collect; supports `**/*` glob, specific filenames, and path patterns
- `artifacts.base-directory` — base directory for artifact collection (default: `$CODEBUILD_SRC_DIR`)

The agent reads every other part of the buildspec as CodeBuild does, `env.shell` among them. Floci reads the `env.parameter-store` and `env.secrets-manager` entries itself and hands the agent the buildspec without them.

## Artifact Upload

When `artifacts.type=S3`, collected files are uploaded to the configured S3 bucket. The bucket must exist (created via `CreateBucket`). File paths in S3 match the relative path from the artifact base directory.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEBUILD_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CODEBUILD_DOCKER_NETWORK` | Floci's own network | The Docker network a build's agent and containers join |
| `FLOCI_SERVICES_CODEBUILD_AGENT_IMAGE` | the agent for the Docker host's architecture, by digest | The image of CodeBuild's local agent a build runs in |
| `FLOCI_SERVICES_CODEBUILD_AGENT_WORKSPACE` | `floci-codebuild` under `$SLUICE_TMP`, else `/tmp/floci-codebuild-<floci.port>` | Where each build's working folder is made; the same path on the Docker host and in Floci's container |

## CLI Examples

```bash
# Create a project with S3 artifacts
aws --endpoint-url http://localhost:4566 codebuild create-project \
  --name my-project \
  --source type=NO_SOURCE \
  --artifacts type=S3,location=my-bucket \
  --environment type=LINUX_CONTAINER,image=public.ecr.aws/docker/library/alpine:latest,computeType=BUILD_GENERAL1_SMALL \
  --service-role arn:aws:iam::000000000000:role/codebuild-role

# Start a build with inline buildspec
aws --endpoint-url http://localhost:4566 codebuild start-build \
  --project-name my-project \
  --buildspec-override 'version: 0.2
phases:
  build:
    commands:
      - echo hello > output.txt
artifacts:
  files:
    - output.txt'

# Poll until complete
aws --endpoint-url http://localhost:4566 codebuild batch-get-builds --ids <build-id>

# List all builds
aws --endpoint-url http://localhost:4566 codebuild list-builds

# List curated images
aws --endpoint-url http://localhost:4566 codebuild list-curated-environment-images
```
