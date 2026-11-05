import * as pulumi from "@pulumi/pulumi";

// what the program needs, read once from the stack's config. secrets stay pulumi secrets end to end:
// encrypted in the stack file and the state, never plain in a ConfigMap or a pod spec (ADR-0030)
export interface Resources {
  requests: { cpu: string; memory: string };
  limits: { memory: string };
}

export interface Settings {
  image: string;
  imagePullSecret?: string;
  namespace: string;
  publicBaseUrl: string;
  ingressClassName?: string;
  tlsSecretName?: string;
  apiReplicas: number;
  workerReplicas: number;
  apiResources: Resources;
  workerResources: Resources;
  // spring.lifecycle.timeout-per-shutdown-phase of the app (30 s): the grace period covers it, the preStop pause and a margin
  shutdownSeconds: number;
  db: {
    host: string;
    port: number;
    name: string;
    username: string;
    password: pulumi.Output<string>;
    // a PostgreSQL 18 StatefulSet in the namespace: development and trials only
    inCluster: boolean;
    storage: string;
  };
  jwtSecret: pulumi.Output<string>;
  // key id -> base64 of 32 bytes. the id becomes a variable suffix, so lower-case letters and digits only
  encryptionKeys: pulumi.Output<Record<string, string>>;
  activeKey: string;
  // non-secret variables of the app (APP_CONNECTORS_*, WASICHAI_INTEGRATIONS_OAUTH_RETURNURL...)
  env: Record<string, string>;
}

const DEFAULT_RESOURCES: Resources = { requests: { cpu: "250m", memory: "768Mi" }, limits: { memory: "1Gi" } };
const KEY_ID = /^[a-z0-9]{1,32}$/;
// an in-cluster database is reached through this service
export const IN_CLUSTER_DB_HOST = "postgres";

export function readSettings(config: pulumi.Config): Settings {
  const inCluster = config.getBoolean("postgresInCluster") ?? false;
  const activeKey = config.require("activeKey");
  if (!KEY_ID.test(activeKey)) throw new Error(`activeKey '${activeKey}': lower-case letters and digits only`);
  const publicBaseUrl = config.require("publicBaseUrl");
  if (!/^https?:\/\//.test(publicBaseUrl)) throw new Error("publicBaseUrl must be an http(s) url");
  const env = config.getObject<Record<string, string>>("env") ?? {};
  for (const name of Object.keys(env)) {
    // a secret here would land in a ConfigMap: refuse the names that carry one
    if (/PASSWORD|SECRET|_KEYS_|TOKEN/.test(name)) throw new Error(`env.${name} looks secret: set it as a pulumi secret instead`);
  }
  return {
    image: config.require("image"),
    imagePullSecret: config.get("imagePullSecret"),
    namespace: config.get("namespace") ?? "wasichai-integrations",
    publicBaseUrl,
    ingressClassName: config.get("ingressClassName"),
    tlsSecretName: config.get("tlsSecretName"),
    apiReplicas: config.getNumber("apiReplicas") ?? 2,
    workerReplicas: config.getNumber("workerReplicas") ?? 1,
    apiResources: config.getObject<Resources>("apiResources") ?? DEFAULT_RESOURCES,
    workerResources: config.getObject<Resources>("workerResources") ?? DEFAULT_RESOURCES,
    shutdownSeconds: config.getNumber("shutdownSeconds") ?? 30,
    db: {
      host: inCluster ? IN_CLUSTER_DB_HOST : config.require("dbHost"),
      port: config.getNumber("dbPort") ?? 5432,
      name: config.get("dbName") ?? "wasichai",
      username: config.get("dbUsername") ?? "wasichai",
      password: config.requireSecret("dbPassword"),
      inCluster,
      storage: config.get("postgresStorage") ?? "5Gi",
    },
    jwtSecret: config.requireSecret("jwtSecret"),
    // checked inside the secret: the ids are only known once it is decrypted
    encryptionKeys: config.requireSecretObject<Record<string, string>>("encryptionKeys").apply((keys) => checkKeys(keys, activeKey)),
    activeKey,
    env,
  };
}

export function checkKeys(keys: Record<string, string>, activeKey: string): Record<string, string> {
  for (const id of Object.keys(keys)) if (!KEY_ID.test(id)) throw new Error(`encryptionKeys.${id}: lower-case letters and digits only`);
  if (!(activeKey in keys)) throw new Error(`activeKey '${activeKey}' is not one of encryptionKeys`);
  return keys;
}
