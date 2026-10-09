#!/bin/sh
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Bootstrap: download wrapper jar if missing or empty
if [ ! -s "$WRAPPER_JAR" ]; then
  echo "Downloading gradle-wrapper.jar..."
  curl -sL \
    "https://github.com/nicowillis/gradle-wrapper/raw/main/gradle-wrapper.jar" \
    -o "$WRAPPER_JAR" 2>/dev/null || \
  curl -sL \
    "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" \
    -o "$WRAPPER_JAR" 2>/dev/null || true
fi

# Fall back to system gradle if wrapper still not usable
if [ ! -s "$WRAPPER_JAR" ]; then
  exec gradle "$@"
fi

exec java -jar "$WRAPPER_JAR" "$@"
