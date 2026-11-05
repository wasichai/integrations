import * as pulumi from "@pulumi/pulumi";

// pulumi's own mocks: no engine, no cluster. every resource the program registers is recorded with its inputs
export interface Registered {
  type: string;
  name: string;
  inputs: any;
}

export const registered: Registered[] = [];

export const PROJECT = "wasichai-integrations";

export function mock(config: Record<string, string>, secretKeys: string[]): void {
  pulumi.runtime.setAllConfig(
    Object.fromEntries(Object.entries(config).map(([k, v]) => [`${PROJECT}:${k}`, v])),
    secretKeys.map((k) => `${PROJECT}:${k}`),
  );
  pulumi.runtime.setMocks(
    {
      newResource: (args) => {
        registered.push({ type: args.type, name: args.name, inputs: args.inputs });
        // an auto-named resource gets a generated name, as the provider would give it
        const metadata = { ...args.inputs.metadata, name: args.inputs.metadata?.name ?? `${args.name}-0a1b2c3d` };
        return { id: `${args.name}-id`, state: { ...args.inputs, metadata } };
      },
      call: (args) => args.inputs,
    },
    PROJECT,
    "ci",
    false,
  );
}

export function resolve<T>(output: pulumi.Output<T>): Promise<T> {
  return new Promise((done) => output.apply((value) => done(value)));
}

export function one(type: string, name: string): Registered {
  const found = registered.filter((r) => r.type === type && r.name === name);
  if (found.length !== 1) throw new Error(`${found.length} ${type} named ${name}: ${registered.map((r) => `${r.type} ${r.name}`).join(", ")}`);
  return found[0];
}

export function container(deployment: Registered): any {
  return deployment.inputs.spec.template.spec.containers[0];
}

// placeholder values: only their absence from plain resources is checked
export const SECRETS = { dbPassword: "db-pass-placeholder", jwtSecret: "jwt-secret-placeholder-0123456789abcdef", key: "a2V5LXBsYWNlaG9sZGVy" };

export const BASE: Record<string, string> = {
  image: "registry.example.test/wasichai-integrations-app:1.2.3",
  publicBaseUrl: "https://integrations.example.test",
  dbHost: "db.example.test",
  activeKey: "k1",
  dbPassword: SECRETS.dbPassword,
  jwtSecret: SECRETS.jwtSecret,
  encryptionKeys: JSON.stringify({ k1: SECRETS.key }),
};

export const SECRET_KEYS = ["dbPassword", "jwtSecret", "encryptionKeys"];
