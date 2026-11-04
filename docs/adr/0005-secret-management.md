# ADR-0005: Credentials are encrypted with AES-256-GCM under named keys from a key provider

**Status**: accepted · 2026-11-04

## Context

Wasichai has no encryption at rest: JWT signing and BCrypt-hashed service-account secrets only. Integration
credentials must be decrypted to be used, so hashing is not an option.

## Decision

- `CredentialVault` encrypts with AES-256-GCM, a fresh 96-bit nonce per value, and additional authenticated data of
  organization id and owner row id. Rows store ciphertext, nonce and `key_id`.
- `CredentialKeyProvider` supplies keys by id and names the active one. The default reads a key ring from configuration
  (`wasichai.integrations.vault`), so keys come from the environment or the operator's secret store; a KMS or Vault
  implementation is a bean that replaces it. This SPI exists without a second implementation because production
  deployments must be able to keep keys out of the app's configuration; the seam is the requirement.
- Rotation adds a key and makes it active; reads use the row's key id; a re-encrypt job moves old rows. Losing all keys
  means reconnecting, by design.
- Secrets are write-only over REST, masked in answers, never logged.

## Consequences

- The app fails to start with the module on and no key configured, rather than storing anything in plaintext.
