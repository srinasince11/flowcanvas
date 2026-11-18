#!/usr/bin/env bash
# Starts the FlowCanvas backend for local verification.
# Usage: ./scripts/dev-backend.sh start|stop|restart|status
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/backend/target/flowcanvas-backend-1.0.0.jar"
PID_FILE="/tmp/flowcanvas-backend.pid"
LOG_FILE="/tmp/flowcanvas-backend.log"

export JAVA_HOME="${JAVA_HOME:-/opt/java/jdk-21.0.13+9}"
export PATH="$JAVA_HOME/bin:$PATH"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/flowcanvas}"
export DB_USERNAME="${DB_USERNAME:-postgres}"
export DB_PASSWORD="${DB_PASSWORD:-postgres}"

start() {
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "already running (pid $(cat "$PID_FILE"))"
    return 0
  fi
  [ -f "$JAR" ] || { echo "jar not found: $JAR — run 'mvn -DskipTests package' first" >&2; exit 1; }
  nohup java -jar "$JAR" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  for _ in $(seq 1 60); do
    if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
      echo "backend up (pid $(cat "$PID_FILE"))"
      return 0
    fi
    sleep 1
  done
  echo "backend did not become healthy; last log lines:" >&2
  tail -20 "$LOG_FILE" >&2
  exit 1
}

stop() {
  if [ -f "$PID_FILE" ]; then
    kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "stopped"
  else
    echo "not running"
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 2; start ;;
  status) curl -s http://localhost:8080/actuator/health || echo "down" ;;
  *) echo "usage: $0 start|stop|restart|status" >&2; exit 2 ;;
esac
