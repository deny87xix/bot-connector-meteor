#!/usr/bin/env bash
# usage: write-report.sh <outcome>
set -u
mkdir -p docs/ci
{
  echo "status: $1"
  echo "commit: ${GITHUB_SHA:-local}"
  echo "time: $(date -u +%FT%TZ)"
  echo "run: ${GITHUB_SERVER_URL:-}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"
  echo
  echo "--- javac errors ---"
  grep -E -A3 ' error: ' build.log | head -n 250
  echo
  echo "--- javac warnings (first 40) ---"
  grep -E ' warning: ' build.log | head -n 40
  echo
  echo "--- gradle failure ---"
  grep -E -A12 'What went wrong' build.log | head -n 60
  echo
  echo "--- tail of log ---"
  tail -n 40 build.log
} > docs/ci/last-build.txt
