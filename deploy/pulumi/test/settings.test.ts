import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import * as pulumi from "@pulumi/pulumi";
import { BASE, mock, SECRET_KEYS } from "./mocks";
import { checkKeys, readSettings } from "../settings";

function settings(overrides: Record<string, string> = {}) {
  mock({ ...BASE, ...overrides }, SECRET_KEYS);
  return readSettings(new pulumi.Config());
}

describe("settings", () => {
  it("reads secrets as pulumi secrets", async () => {
    const s = settings();
    assert.equal(await pulumi.isSecret(s.jwtSecret), true);
    assert.equal(await pulumi.isSecret(s.db.password), true);
    assert.equal(await pulumi.isSecret(s.encryptionKeys), true);
    assert.equal(s.apiReplicas, 2);
    assert.equal(s.workerReplicas, 1);
    assert.equal(s.shutdownSeconds, 30);
  });

  it("refuses a secret-looking variable in the plain env", () => {
    assert.throws(() => settings({ env: JSON.stringify({ WASICHAI_JWT_SECRET: "x" }) }), /looks secret/);
  });

  it("refuses an active key that is not among the keys, and a key id no variable can carry", () => {
    assert.throws(() => checkKeys({ k1: "x" }, "k2"), /not one of encryptionKeys/);
    assert.throws(() => checkKeys({ "k-1": "x" }, "k-1"), /lower-case letters and digits/);
    assert.deepEqual(checkKeys({ k1: "x", k2: "y" }, "k2"), { k1: "x", k2: "y" });
  });

  it("refuses a public url without a scheme", () => {
    assert.throws(() => settings({ publicBaseUrl: "integrations.example.test" }), /http\(s\) url/);
  });

  it("the ci stack holds placeholders and no secret", () => {
    const stack = readFileSync(join(__dirname, "..", "Pulumi.ci.yaml"), "utf8");
    const keys = [...stack.matchAll(/^ {2}wasichai-integrations:(\w+):/gm)].map((m) => m[1]);
    assert.ok(keys.includes("image") && keys.includes("publicBaseUrl"));
    for (const secret of SECRET_KEYS) assert.ok(!keys.includes(secret), `${secret} belongs in a secret, not the stack file`);
  });
});
