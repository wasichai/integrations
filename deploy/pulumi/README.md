# Kubernetes with Pulumi

A [Pulumi](https://www.pulumi.com/) program (TypeScript, the Kubernetes provider only) that runs the example image of
[ADR-0028](../../docs/adr/0028-example-app-and-image.md) on any Kubernetes cluster, k3s included: no cloud provider,
no cloud account ([ADR-0030](../../docs/adr/0030-shared-rate-limits-and-kubernetes.md)).

| Resource | What it is |
|---|---|
| `Namespace` | `namespace` (default `wasichai-integrations`) |
| `Secret` (auto-named) | `WASICHAI_DB_PASSWORD`, `WASICHAI_JWT_SECRET`, `WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_<ID>` from Pulumi secrets |
| `ConfigMap` (auto-named) | the database address, public url, active key id, `WASICHAI_INTEGRATIONS_HTTP_RATELIMIT_SHARED=true`, `SERVER_FORWARDHEADERSSTRATEGY=framework` and `env` |
| `Deployment` `integrations-api` | `SPRING_PROFILES_ACTIVE=api`, `apiReplicas` (default 2) |
| `Deployment` `integrations-worker` | `SPRING_PROFILES_ACTIVE=worker`, `workerReplicas` (default 1), no Service |
| `Service` `integrations-api` | port 80 to the api pods' 8080 |
| `Ingress` `integrations` | the host of `publicBaseUrl`, TLS when `tlsSecretName` is set |
| `StatefulSet` + `Service` `postgres` | only with `postgresInCluster=true`: one PostgreSQL 18 pod with a volume, for development |

Both Deployments run as uid 10001 with a read-only root filesystem (an `emptyDir` on `/tmp`), no privilege escalation
and every capability dropped. A startup probe on `/actuator/health/liveness` gives the first start (it migrates every
module) up to 5 minutes; then liveness and readiness probe `/actuator/health/liveness` and `/actuator/health/readiness`.
On a stop, a 5 s `preStop` pause lets the Service drop the pod, the app drains for `shutdownSeconds` (30, the image's
`spring.lifecycle.timeout-per-shutdown-phase`) and the grace period is 45 s. The `preStop` sleep action needs
Kubernetes 1.30 or later. A changed setting or secret makes a new ConfigMap or Secret, and the Deployments roll onto it.
Every replica runs the Flyway migrations at start under PostgreSQL's advisory lock, so the roles may start together.

## Config

| Key | Default | Meaning |
|---|---|---|
| `image` | **required** | the image to run, e.g. `ghcr.io/acme/wasichai-integrations-app:1.4.0` |
| `imagePullSecret` | none | a `docker-registry` Secret in the namespace for a private registry |
| `namespace` | `wasichai-integrations` | created by the program |
| `publicBaseUrl` | **required** | `https://integrations.example.com`: the app's public url and the Ingress host |
| `ingressClassName` | the cluster's default | `traefik` on k3s, `nginx`... |
| `tlsSecretName` | none | a TLS Secret in the namespace (cert-manager, or made by hand) |
| `apiReplicas`, `workerReplicas` | `2`, `1` | at least one worker must run |
| `apiResources`, `workerResources` | requests `250m` / `768Mi`, limit `1Gi` | `{"requests":{"cpu":"…","memory":"…"},"limits":{"memory":"…"}}`; the heap is 75% of the limit |
| `shutdownSeconds` | `30` | the app's drain time; the grace period follows |
| `dbHost` | **required** unless `postgresInCluster` | PostgreSQL 18 |
| `dbPort`, `dbName`, `dbUsername` | `5432`, `wasichai`, `wasichai` | |
| `dbPassword` | **required secret** | |
| `jwtSecret` | **required secret** | at least 32 bytes: `openssl rand -base64 48` |
| `encryptionKeys` | **required secret** | `{"k1":"<openssl rand -base64 32>"}`; ids in lower-case letters and digits |
| `activeKey` | **required** | the key id new secrets are sealed with; one of `encryptionKeys` |
| `postgresInCluster`, `postgresStorage` | `false`, `5Gi` | an in-cluster PostgreSQL 18 for trials; production uses a managed or operated database |
| `env` | `{}` | other non-secret variables: `WASICHAI_INTEGRATIONS_OAUTH_RETURNURL`, `WASICHAI_WEB_CORSALLOWEDORIGINPATTERNS`, `APP_CONNECTORS_*`... (the [deployment guide](../../docs/guides/deployment.md#environment-variables)); a name with `PASSWORD`, `SECRET`, `TOKEN` or `_KEYS_` is refused |

## Run it

Node 22 or later, the [Pulumi CLI](https://www.pulumi.com/docs/iac/download-install/) and a kubeconfig for the cluster
(`KUBECONFIG`, or `~/.kube/config`; k3s writes `/etc/rancher/k3s/k3s.yaml`).

```bash
cd deploy/pulumi
npm ci
pulumi login --local                       # or any pulumi backend
pulumi stack init prod                     # a passphrase encrypts the stack's secrets
pulumi config set image ghcr.io/acme/wasichai-integrations-app:1.4.0
pulumi config set publicBaseUrl https://integrations.example.com
pulumi config set dbHost db.internal.example.com
pulumi config set activeKey k1
pulumi config set --secret dbPassword "$(openssl rand -hex 24)"
pulumi config set --secret jwtSecret "$(openssl rand -base64 48)"
pulumi config set --secret --path 'encryptionKeys.k1' "$(openssl rand -base64 32)"
pulumi config set --path 'env.WASICHAI_INTEGRATIONS_OAUTH_RETURNURL' https://ui.example.com/integrations/connections
pulumi preview
pulumi up
```

Back the encryption keys up with the database: the stack file holds them encrypted with the stack's passphrase (or
the backend's key), and losing them makes every stored credential unreadable. To rotate a key, add the new id to
`encryptionKeys`, switch `activeKey`, `pulumi up`, and keep the old id until no row uses it (the
[deployment guide](../../docs/guides/deployment.md#encryption-keys-and-rotation)).

[`Pulumi.ci.yaml`](Pulumi.ci.yaml) is a placeholder stack with an in-cluster database; it holds no secret. To preview
it against a local cluster (k3d, kind or k3s):

```bash
pulumi login --local && export PULUMI_CONFIG_PASSPHRASE=local
pulumi stack init ci
pulumi config set --secret dbPassword x && pulumi config set --secret jwtSecret x
pulumi config set --secret --path 'encryptionKeys.k1' x
pulumi preview --stack ci
git checkout Pulumi.ci.yaml                # the secrets above were written into it
```

## Tests

```bash
npm ci && npm run typecheck && npm test
```

The tests run the program against Pulumi's mocks (`pulumi.runtime.setMocks`): no engine, no cluster, no credentials.
They assert the roles, probes, grace period, security context, Ingress, that secrets stay Pulumi secrets and appear in
nothing but the Secret, and the in-cluster database. CI runs them; it does not run `pulumi preview`, which needs a
cluster to ask (ADR-0030).
