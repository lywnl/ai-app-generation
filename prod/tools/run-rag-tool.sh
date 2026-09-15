#!/usr/bin/env bash
set -Eeuo pipefail
operation="${1:-verify}"
case "$operation" in ingest|verify|catalog) ;; *) exit 64 ;; esac
runtime=$(mktemp -d /tmp/rag-deploy.XXXXXX)
trap 'rm -rf "$runtime"' EXIT
cd "$runtime"
jar xf /app/app.jar BOOT-INF/classes BOOT-INF/lib
classpath="$runtime/BOOT-INF/classes:$runtime/BOOT-INF/lib/*"
javac -encoding UTF-8 -cp "$classpath" -d "$runtime" /app/deployment-tools/RagDeploymentTool.java
java -Xmx512m -cp "$runtime:$classpath" RagDeploymentTool "$operation"
