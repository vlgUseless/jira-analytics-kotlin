#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$APP_HOME"
exec "$APP_HOME/bin/jira-analytics-kotlin" "$APP_HOME/config/app.properties" "$@"
