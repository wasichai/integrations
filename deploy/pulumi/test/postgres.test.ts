import assert from "node:assert/strict";
import { before, describe, it } from "node:test";
import * as pulumi from "@pulumi/pulumi";
import { BASE, mock, one, resolve, SECRET_KEYS } from "./mocks";

const { dbHost: _external, ...rest } = BASE;
mock({ ...rest, postgresInCluster: "true", postgresStorage: "2Gi" }, SECRET_KEYS);

describe("in-cluster postgresql", () => {
  before(async () => {
    const { deploy } = await import("../program");
    const { readSettings } = await import("../settings");
    const d = deploy(readSettings(new pulumi.Config()));
    await resolve(pulumi.all([d.postgres!.id, d.api.id, d.worker.id, d.config.id]));
  });

  it("runs postgres 18 with a volume and the password from the Secret", () => {
    const pg = one("kubernetes:apps/v1:StatefulSet", "wasichai-integrations-postgres");
    const c = pg.inputs.spec.template.spec.containers[0];
    assert.equal(c.image, "postgres:18");
    assert.equal(c.env.find((e: any) => e.name === "POSTGRES_PASSWORD").valueFrom.secretKeyRef.key, "WASICHAI_DB_PASSWORD");
    assert.equal(pg.inputs.spec.volumeClaimTemplates[0].spec.resources.requests.storage, "2Gi");
    assert.equal(one("kubernetes:core/v1:Service", "wasichai-integrations-postgres").inputs.metadata.name, "postgres");
  });

  it("points the app at it", () => {
    assert.equal(one("kubernetes:core/v1:ConfigMap", "wasichai-integrations-config").inputs.data.WASICHAI_DB_HOST, "postgres");
  });
});
