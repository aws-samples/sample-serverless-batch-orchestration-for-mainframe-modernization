#!/bin/bash
# =============================================================================
# entrypoint.sh — FAITHFUL REFERENCE (mirrors the real Gapwalk batch container).
#
# The single image bakes in every job; the ECS Command override selects one by
# name. Here that name is a Groovy JCL script served by the Gapwalk webapp. The
# container exit code is derived from the job's Groovy exitCode, which Step
# Functions reads via Containers[N].ExitCode.
# =============================================================================
set -e

SCRIPT_NAME=${1:-JOB1}   # ECS Command override, e.g. ["JOB2A"]

echo "Starting Tomcat (Gapwalk runtime)..."
catalina.sh start

echo "Waiting for gapwalk-application..."
for i in {1..60}; do
  if curl -s -f http://localhost:8080/gapwalk-application/ > /dev/null 2>&1; then break; fi
  if [ "$i" -eq 60 ]; then echo "Tomcat failed to start"; catalina.sh stop; exit 1; fi
  sleep 1
done

echo "Executing $SCRIPT_NAME ..."
RESPONSE=$(curl -s -w "\n%{http_code}" --max-time 600 \
  "http://localhost:8080/gapwalk-application/script/$SCRIPT_NAME")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$HTTP_CODE" != "200" ]; then
  echo "HTTP $HTTP_CODE from Gapwalk"; cat /opt/tomcat/logs/catalina.out || true
  catalina.sh stop; exit 1
fi

# The job's Groovy return code is surfaced as "exitCode" in the JSON body.
EXIT_CODE=$(echo "$BODY" | sed -n 's/.*"exitCode"[[:space:]]*:[[:space:]]*\(-\{0,1\}[0-9]\+\).*/\1/p')
catalina.sh stop

if [ "$EXIT_CODE" = "0" ]; then
  echo "$SCRIPT_NAME succeeded"; exit 0
else
  echo "$SCRIPT_NAME failed (exitCode=$EXIT_CODE)"; exit 1
fi
