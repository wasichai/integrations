import * as k8s from "@pulumi/kubernetes";
import * as pulumi from "@pulumi/pulumi";
import { Resources, Settings } from "./settings";

// the example image of ADR-0028 on any Kubernetes (k3s included): one image in two roles, `api` behind a Service and
// an Ingress, `worker` without one, settings in a ConfigMap and secrets in a Secret (ADR-0030)

const APP = "wasichai-integrations";
const PORT = 8080;
// the endpoints controller needs a moment to take a stopping pod out of the Service before java sees SIGTERM
const PRE_STOP_SECONDS = 5;
const UID = 10001;

export interface Deployed {
  namespace: k8s.core.v1.Namespace;
  config: k8s.core.v1.ConfigMap;
  secret: k8s.core.v1.Secret;
  api: k8s.apps.v1.Deployment;
  worker: k8s.apps.v1.Deployment;
  service: k8s.core.v1.Service;
  ingress: k8s.networking.v1.Ingress;
  postgres?: k8s.apps.v1.StatefulSet;
}

export function deploy(s: Settings): Deployed {
  const namespace = new k8s.core.v1.Namespace(APP, { metadata: { name: s.namespace, labels: labels("namespace") } });
  const ns = namespace.metadata.name;

  // auto-named: a changed value makes a new ConfigMap or Secret, and the Deployments roll onto it
  const secret = new k8s.core.v1.Secret(`${APP}-secrets`, {
    metadata: { namespace: ns, labels: labels("secrets") },
    type: "Opaque",
    stringData: pulumi.all([s.db.password, s.jwtSecret, s.encryptionKeys]).apply(([password, jwt, keys]) => ({
      WASICHAI_DB_PASSWORD: password,
      WASICHAI_JWT_SECRET: jwt,
      ...Object.fromEntries(Object.entries(keys).map(([id, key]) => [`WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_${id.toUpperCase()}`, key])),
    })),
  });

  const config = new k8s.core.v1.ConfigMap(`${APP}-config`, {
    metadata: { namespace: ns, labels: labels("config") },
    data: {
      WASICHAI_DB_HOST: s.db.host,
      WASICHAI_DB_PORT: String(s.db.port),
      WASICHAI_DB_NAME: s.db.name,
      WASICHAI_DB_USERNAME: s.db.username,
      WASICHAI_INTEGRATIONS_PUBLICBASEURL: s.publicBaseUrl,
      WASICHAI_INTEGRATIONS_ENCRYPTION_ACTIVEKEY: s.activeKey,
      // several replicas: one rate budget per connection and per ai tool, in postgresql
      WASICHAI_INTEGRATIONS_HTTP_RATELIMIT_SHARED: "true",
      // behind the ingress: the client address (sign-in limits) and the scheme come from the forwarded headers
      SERVER_FORWARDHEADERSSTRATEGY: "framework",
      SPRING_LIFECYCLE_TIMEOUTPERSHUTDOWNPHASE: `${s.shutdownSeconds}s`,
      ...s.env,
    },
  });

  const postgres = s.db.inCluster ? inClusterPostgres(s, ns, secret) : undefined;

  const role = (component: "api" | "worker", replicas: number, resources: Resources) =>
    new k8s.apps.v1.Deployment(
      `${APP}-${component}`,
      {
        metadata: { name: `integrations-${component}`, namespace: ns, labels: labels(component) },
        spec: {
          replicas,
          selector: { matchLabels: selector(component) },
          template: {
            metadata: { labels: labels(component) },
            spec: {
              terminationGracePeriodSeconds: s.shutdownSeconds + PRE_STOP_SECONDS + 10,
              automountServiceAccountToken: false,
              imagePullSecrets: s.imagePullSecret ? [{ name: s.imagePullSecret }] : undefined,
              securityContext: {
                runAsNonRoot: true,
                runAsUser: UID,
                runAsGroup: UID,
                fsGroup: UID,
                seccompProfile: { type: "RuntimeDefault" },
              },
              containers: [
                {
                  name: component,
                  image: s.image,
                  ports: [{ name: "http", containerPort: PORT }],
                  env: [{ name: "SPRING_PROFILES_ACTIVE", value: component }],
                  envFrom: [{ configMapRef: { name: config.metadata.name } }, { secretRef: { name: secret.metadata.name } }],
                  resources,
                  // the first start migrates every module: up to 5 minutes before liveness counts
                  startupProbe: probe("/actuator/health/liveness", 60),
                  livenessProbe: probe("/actuator/health/liveness", 3),
                  readinessProbe: probe("/actuator/health/readiness", 3),
                  lifecycle: { preStop: { sleep: { seconds: PRE_STOP_SECONDS } } },
                  securityContext: {
                    allowPrivilegeEscalation: false,
                    readOnlyRootFilesystem: true,
                    capabilities: { drop: ["ALL"] },
                  },
                  // the jvm and netty write temporary files; the root filesystem stays read-only
                  volumeMounts: [{ name: "tmp", mountPath: "/tmp" }],
                },
              ],
              volumes: [{ name: "tmp", emptyDir: {} }],
            },
          },
        },
      },
      { dependsOn: postgres ? [postgres] : [] },
    );

  const api = role("api", s.apiReplicas, s.apiResources);
  // jobs and schedulers; answers http for its probes only, so no Service
  const worker = role("worker", s.workerReplicas, s.workerResources);

  const service = new k8s.core.v1.Service(`${APP}-api`, {
    metadata: { name: "integrations-api", namespace: ns, labels: labels("api") },
    spec: { selector: selector("api"), ports: [{ name: "http", port: 80, targetPort: "http" }] },
  });

  const host = new URL(s.publicBaseUrl).hostname;
  const ingress = new k8s.networking.v1.Ingress(APP, {
    metadata: { name: "integrations", namespace: ns, labels: labels("api") },
    spec: {
      ingressClassName: s.ingressClassName,
      tls: s.tlsSecretName ? [{ hosts: [host], secretName: s.tlsSecretName }] : undefined,
      rules: [
        {
          host,
          http: {
            paths: [{ path: "/", pathType: "Prefix", backend: { service: { name: service.metadata.name, port: { name: "http" } } } }],
          },
        },
      ],
    },
  });

  return { namespace, config, secret, api, worker, service, ingress, postgres };
}

// one PostgreSQL 18 pod with a volume: for development and trials. production points dbHost at a managed database
function inClusterPostgres(s: Settings, ns: pulumi.Output<string>, secret: k8s.core.v1.Secret): k8s.apps.v1.StatefulSet {
  new k8s.core.v1.Service(`${APP}-postgres`, {
    metadata: { name: "postgres", namespace: ns, labels: labels("postgres") },
    spec: { selector: selector("postgres"), ports: [{ name: "postgres", port: 5432, targetPort: "postgres" }] },
  });
  return new k8s.apps.v1.StatefulSet(`${APP}-postgres`, {
    metadata: { name: "postgres", namespace: ns, labels: labels("postgres") },
    spec: {
      serviceName: "postgres",
      replicas: 1,
      selector: { matchLabels: selector("postgres") },
      template: {
        metadata: { labels: labels("postgres") },
        spec: {
          // the postgres image's own user
          securityContext: { runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: { type: "RuntimeDefault" } },
          containers: [
            {
              name: "postgres",
              image: "postgres:18",
              ports: [{ name: "postgres", containerPort: 5432 }],
              env: [
                { name: "POSTGRES_DB", value: s.db.name },
                { name: "POSTGRES_USER", value: s.db.username },
                { name: "POSTGRES_PASSWORD", valueFrom: { secretKeyRef: { name: secret.metadata.name, key: "WASICHAI_DB_PASSWORD" } } },
              ],
              readinessProbe: { exec: { command: ["sh", "-c", 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'] }, periodSeconds: 5 },
              securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } },
              // postgres 18 images keep their data under /var/lib/postgresql/18/docker: mount the parent
              volumeMounts: [{ name: "data", mountPath: "/var/lib/postgresql" }],
            },
          ],
        },
      },
      volumeClaimTemplates: [
        {
          metadata: { name: "data" },
          spec: { accessModes: ["ReadWriteOnce"], resources: { requests: { storage: s.db.storage } } },
        },
      ],
    },
  });
}

function probe(path: string, failureThreshold: number): k8s.types.input.core.v1.Probe {
  return { httpGet: { path, port: "http" }, periodSeconds: 5, timeoutSeconds: 3, failureThreshold };
}

function selector(component: string): Record<string, string> {
  return { "app.kubernetes.io/name": APP, "app.kubernetes.io/component": component };
}

function labels(component: string): Record<string, string> {
  return { ...selector(component), "app.kubernetes.io/managed-by": "pulumi" };
}
