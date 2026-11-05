#!/usr/bin/env bash
# container smoke test (ADR-0028): the image runs as the api and the worker service of deploy/compose on postgres 18,
# both answer their healthcheck, the seeded admin signs in and the catalog lists the shipped connectors.
# usage: smoke.sh <image>                     compose up with that (already built) image, check, compose down
#        smoke.sh --checks-only <base-url>    the http checks only, against an app already running with
#                                             WASICHAI_SEED_DEV=true (e.g. the jar on a local database)
# needs docker compose v2, curl, jq and openssl. writes deploy/compose/.env, so refuses to run over an existing one.
set -euo pipefail

expected="github jira notion salesforce slack"
admin='{"email":"admin@wasichai.local","password":"admin"}'

log() { echo "smoke: $*" >&2; }

# up to 3 minutes: flyway migrates every module on the first start
wait_healthy() {
  local base="$1"
  for _ in $(seq 1 90); do
    if curl -fsS "$base/actuator/health" 2>/dev/null | jq -e '.status == "UP"' > /dev/null; then
      return 0
    fi
    sleep 2
  done
  log "$base/actuator/health never answered UP"
  return 1
}

checks() {
  local base="$1" token actual status
  wait_healthy "$base"
  log "health UP"

  status="$(curl -s -o /dev/null -w '%{http_code}' "$base/api/integrations/connectors")"
  if [ "$status" != "401" ]; then
    log "the catalog without a token answered $status, expected 401"
    return 1
  fi

  token="$(curl -fsS -H 'Content-Type: application/json' -d "$admin" "$base/api/auth/login" | jq -er '.token')"
  log "signed in as the seeded admin"

  actual="$(curl -fsS -H "Authorization: Bearer $token" "$base/api/integrations/connectors" | jq -r '[.[].id] | sort | join(" ")')"
  if [ "$actual" != "$expected" ]; then
    log "catalog lists '$actual', expected '$expected'"
    return 1
  fi
  log "catalog lists $actual"
}

if [ "${1:-}" = "--checks-only" ]; then
  checks "${2:?usage: smoke.sh --checks-only <base-url>}"
  log "ok"
  exit 0
fi

image="${1:?usage: smoke.sh <image> | smoke.sh --checks-only <base-url>}"
port="${SMOKE_PORT:-8080}"
dir="$(cd "$(dirname "$0")/../../deploy/compose" && pwd)"
env_file="$dir/.env"
compose=(docker compose --project-directory "$dir" -f "$dir/compose.yaml" -p integrations-smoke)

if [ -e "$env_file" ]; then
  log "$env_file exists; this script writes its own. move yours away first"
  exit 1
fi

cleanup() {
  local code=$?
  if [ "$code" -ne 0 ]; then
    log "failed; state and logs follow"
    "${compose[@]}" ps -a || true
    "${compose[@]}" logs --no-color --tail 300 || true
  fi
  "${compose[@]}" down -v --remove-orphans || true
  rm -f "$env_file"
  exit "$code"
}
trap cleanup EXIT

# .env from the example, every placeholder replaced; a variable missing from the example fails here
cp "$dir/.env.example" "$env_file"
set_var() {
  grep -q "^$1=" "$env_file" || { log "$1 is not in .env.example"; exit 1; }
  sed -i "s|^$1=.*|$1=$2|" "$env_file"
}
set_var INTEGRATIONS_IMAGE "$image"
set_var API_PORT "$port"
set_var WASICHAI_DB_PASSWORD "$(openssl rand -hex 24)"
set_var WASICHAI_JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
set_var WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_K1 "$(openssl rand -base64 32 | tr -d '\n')"
set_var WASICHAI_SEED_DEV true
if grep -qE '^[A-Z0-9_]+=replace-me' "$env_file"; then
  log "a placeholder is left in .env"
  exit 1
fi

# --wait: every service healthy (postgres by pg_isready, api and worker by the image's HEALTHCHECK)
"${compose[@]}" up -d --no-build --wait --wait-timeout 300
log "compose services healthy"

# the worker publishes no port: ask it from inside its container
"${compose[@]}" exec -T worker curl -fsS http://127.0.0.1:8080/actuator/health | jq -e '.status == "UP"' > /dev/null
log "worker health UP"

checks "http://127.0.0.1:$port"
log "ok"
