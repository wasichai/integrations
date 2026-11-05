import * as pulumi from "@pulumi/pulumi";
import { deploy } from "./program";
import { readSettings } from "./settings";

const deployed = deploy(readSettings(new pulumi.Config()));

export const namespace = deployed.namespace.metadata.name;
export const apiService = deployed.service.metadata.name;
export const ingressHost = deployed.ingress.spec.rules.apply((rules) => rules?.[0]?.host);
