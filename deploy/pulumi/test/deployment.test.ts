import assert from "node:assert/strict";
import { before, describe, it } from "node:test";
import * as pulumi from "@pulumi/pulumi";
import { BASE, container, mock, one, registered, resolve, SECRET_KEYS, SECRETS } from "./mocks";

// the marker the pulumi runtime puts around a secret it serializes
const SECRET_SIG = "4dabf18193072939515e22adb298388d";
const SECRET_VALUE_SIG = "1b47061264138c4ac30d75fd1eb44270";

mock(
  {
    ...BASE,
    apiReplicas: "3",
    workerReplicas: "2",
    tlsSecretName: "integrations-tls",
    ingressClassName: "nginx",
    env: JSON.stringify({ APP_CONNECTORS_SLACK_ENABLED: "false" }),
  },
  SECRET_KEYS,
);

describe("external database", () => {
  before(async () => {
    const { deploy } = await import("../program");
    const { readSettings } = await import("../settings");
    const d = deploy(readSettings(new pulumi.Config()));
    await resolve(pulumi.all([d.namespace.id, d.config.id, d.secret.id, d.api.id, d.worker.id, d.service.id, d.ingress.id]));
  });

  it("runs the api role with probes, a grace period above the shutdown timeout and no root", () => {
    const api = one("kubernetes:apps/v1:Deployment", "wasichai-integrations-api");
    assert.equal(api.inputs.spec.replicas, 3);
    const pod = api.inputs.spec.template.spec;
    const c = container(api);
    assert.equal(c.image, BASE.image);
    assert.deepEqual(c.env, [{ name: "SPRING_PROFILES_ACTIVE", value: "api" }]);
    assert.equal(c.readinessProbe.httpGet.path, "/actuator/health/readiness");
    assert.equal(c.livenessProbe.httpGet.path, "/actuator/health/liveness");
    assert.equal(c.startupProbe.httpGet.path, "/actuator/health/liveness");
    assert.ok(pod.terminationGracePeriodSeconds >= 40, "compose gives 40 s; the app drains for 30");
    assert.equal(pod.securityContext.runAsNonRoot, true);
    assert.equal(pod.securityContext.runAsUser, 10001);
    assert.equal(c.securityContext.allowPrivilegeEscalation, false);
    assert.equal(c.securityContext.readOnlyRootFilesystem, true);
    assert.ok(c.resources.requests.memory && c.resources.limits.memory);
  });

  it("runs the worker role without a service", () => {
    const worker = one("kubernetes:apps/v1:Deployment", "wasichai-integrations-worker");
    assert.equal(worker.inputs.spec.replicas, 2);
    assert.deepEqual(container(worker).env, [{ name: "SPRING_PROFILES_ACTIVE", value: "worker" }]);
    const services = registered.filter((r) => r.type === "kubernetes:core/v1:Service");
    assert.equal(services.length, 1);
    assert.equal(services[0].inputs.spec.selector["app.kubernetes.io/component"], "api");
  });

  it("routes the public host to the api, with tls when a secret is named", () => {
    const ingress = one("kubernetes:networking.k8s.io/v1:Ingress", "wasichai-integrations");
    assert.equal(ingress.inputs.spec.ingressClassName, "nginx");
    assert.equal(ingress.inputs.spec.rules[0].host, "integrations.example.test");
    assert.equal(ingress.inputs.spec.rules[0].http.paths[0].backend.service.name, "integrations-api");
    assert.deepEqual(ingress.inputs.spec.tls, [{ hosts: ["integrations.example.test"], secretName: "integrations-tls" }]);
  });

  it("keeps secrets in the Secret and settings in the ConfigMap", () => {
    const secret = one("kubernetes:core/v1:Secret", "wasichai-integrations-secrets");
    // pulumi's secret envelope: the values reach the state encrypted
    const stringData = secret.inputs.stringData;
    assert.equal(stringData[SECRET_SIG], SECRET_VALUE_SIG);
    assert.deepEqual(Object.keys(stringData.value).sort(), [
      "WASICHAI_DB_PASSWORD",
      "WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_K1",
      "WASICHAI_JWT_SECRET",
    ]);
    const data = one("kubernetes:core/v1:ConfigMap", "wasichai-integrations-config").inputs.data;
    assert.equal(data.WASICHAI_DB_HOST, "db.example.test");
    assert.equal(data.WASICHAI_INTEGRATIONS_ENCRYPTION_ACTIVEKEY, "k1");
    assert.equal(data.WASICHAI_INTEGRATIONS_HTTP_RATELIMIT_SHARED, "true");
    assert.equal(data.APP_CONNECTORS_SLACK_ENABLED, "false");
    // nothing but the Secret carries a secret value
    for (const r of registered.filter((r) => r.type !== "kubernetes:core/v1:Secret")) {
      const plain = JSON.stringify(r.inputs);
      for (const value of Object.values(SECRETS)) assert.ok(!plain.includes(value), `${r.type} ${r.name} holds a secret`);
    }
    const c = container(one("kubernetes:apps/v1:Deployment", "wasichai-integrations-api"));
    assert.deepEqual(
      c.envFrom.map((e: any) => (e.configMapRef ?? e.secretRef).name),
      ["wasichai-integrations-config-0a1b2c3d", "wasichai-integrations-secrets-0a1b2c3d"],
    );
  });

  it("brings no database of its own", () => {
    assert.equal(registered.filter((r) => r.type === "kubernetes:apps/v1:StatefulSet").length, 0);
  });
});
