#!/usr/bin/env bash
# what `./gradlew publish` would upload, checked before it does: publish to a throwaway local
# repository first, then compare the artifact ids with the list below. a new module fails here
# until someone adds it on purpose; the end-to-end suite or the example app (integrations-app) leaking in
# fails here too.
# usage: check-maven-publications.sh <local-repo-dir>   (the dir given to -Dmaven.repo.local)
set -euo pipefail

repo="${1:?usage: check-maven-publications.sh <local-repo-dir>}"

expected="integrations-bom
integrations-connector-github
integrations-connector-jira
integrations-connector-notion
integrations-connector-openapi
integrations-connector-salesforce
integrations-connector-salesforce-pubsub
integrations-connector-sdk
integrations-connector-sdk-test
integrations-connector-slack
wasichai-integrations
wasichai-integrations-ai
wasichai-integrations-sync
wasichai-integrations-workflows
wasichai-spring-boot-starter-integrations
wasichai-spring-boot-starter-integrations-ai
wasichai-spring-boot-starter-integrations-sync
wasichai-spring-boot-starter-integrations-workflows"

if [ ! -d "$repo/wasichai" ]; then
  echo "maven publications: nothing under $repo/wasichai" >&2
  exit 1
fi

# only our artifacts: the local repository also holds what the build downloaded from wasichai
actual="$(ls "$repo/wasichai" | grep -E '^(integrations-|wasichai-integrations|wasichai-spring-boot-starter-integrations)' | LC_ALL=C sort)"
if [ "$actual" != "$(printf '%s\n' "$expected" | LC_ALL=C sort)" ]; then
  echo "maven publications differ from the expected set (< expected, > actual):" >&2
  diff <(printf '%s\n' "$expected" | LC_ALL=C sort) <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi
echo "maven publications: ok ($(printf '%s\n' "$actual" | wc -l | tr -d ' '))"
