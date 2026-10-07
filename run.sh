#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
if [[ $# -gt 1 || ( $# -eq 1 && $1 != demo ) ]]; then
  echo 'Usage: ./run.sh [demo]' >&2
  exit 2
fi
mode=${1:-real}
backend_pid=''
frontend_pid=''
postgres_started=false
cleanup() {
  trap - EXIT INT TERM
  if [[ -n $frontend_pid ]]; then kill "$frontend_pid" 2>/dev/null || true; wait "$frontend_pid" 2>/dev/null || true; fi
  if [[ -n $backend_pid ]]; then kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true; fi
  if [[ $postgres_started == true ]]; then docker compose stop >/dev/null; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Parse plain KEY=VALUE assignments only; never evaluate shell expressions in .env.
shopt -s extglob
if [[ -f .env ]]; then
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line##+([[:space:]])}
    [[ -z $line || $line == \#* ]] && continue
    if [[ $line != *=* ]]; then echo 'Invalid .env line (expected KEY=VALUE).' >&2; exit 1; fi
    key=${line%%=*}
    value=${line#*=}
    key=${key%%+([[:space:]])}
    value=${value##+([[:space:]])}
    value=${value%%+([[:space:]])}
    if [[ ! $key =~ ^[a-zA-Z_][a-zA-Z_0-9]*$ ]]; then echo 'Invalid .env key.' >&2; exit 1; fi
    export "$key=$value"
  done < .env
fi
if [[ ! ${APP_JWT_SECRET+x} ]]; then
  umask 077
  APP_JWT_SECRET=$(openssl rand -hex 32)
  export APP_JWT_SECRET
  printf '\nAPP_JWT_SECRET=%s\n' "$APP_JWT_SECRET" >> .env
fi
if (( ${#APP_JWT_SECRET} < 32 )); then
  echo 'APP_JWT_SECRET must be at least 32 characters; fix its existing value in .env.' >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then echo 'Docker is not running. Start Docker and retry.' >&2; exit 1; fi
docker compose up -d --wait postgres
postgres_started=true
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
if [[ $mode == real ]]; then
  echo 'Warning: a wrong router password can lock the router for a while; the app tries only once.' >&2
fi
./mvnw -q -DskipTests package > /tmp/router-manager-build.log 2>&1 || {
  echo 'Backend build failed; see /tmp/router-manager-build.log' >&2
  tail -n 30 /tmp/router-manager-build.log >&2
  exit 1
}
if [[ $mode == demo ]]; then
  java -jar target/router-manager-*.jar --spring.profiles.active=demo > /tmp/router-manager-backend.log 2>&1 &
else
  java -jar target/router-manager-*.jar > /tmp/router-manager-backend.log 2>&1 &
fi
backend_pid=$!
ready=false
for ((i=0; i<120; i++)); do
  if curl -fsS http://localhost:8092/actuator/health >/dev/null 2>&1; then ready=true; break; fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [[ $ready != true ]]; then
  echo 'Backend did not become healthy within 120 seconds; log tail:' >&2
  tail -n 30 /tmp/router-manager-backend.log >&2
  exit 1
fi
if [[ ! -d frontend/node_modules ]]; then
  (cd frontend && npm install) > /tmp/router-manager-install.log 2>&1 || {
    echo 'Frontend npm install failed; see /tmp/router-manager-install.log' >&2
    tail -n 30 /tmp/router-manager-install.log >&2
    exit 1
  }
fi
(cd frontend && exec npm run dev) > /tmp/router-manager-frontend.log 2>&1 &
frontend_pid=$!
echo 'Open http://localhost:5176 — on first run you will be asked to create the owner account in the browser (or pre-set APP_ADMIN_USERNAME/APP_ADMIN_PASSWORD in .env)'
echo 'Press Ctrl+C to stop the backend, frontend, and PostgreSQL (volume kept).'
wait "$frontend_pid"
