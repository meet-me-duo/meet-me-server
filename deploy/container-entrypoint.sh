#!/bin/sh
set -eu

if [ "${1:-server}" = "migrate" ]; then
    exec java \
        -Dloader.main=com.meetme.server.shared.adapter.output.persistence.DatabaseMigrationRunner \
        -cp /app/app.jar \
        org.springframework.boot.loader.launch.PropertiesLauncher
fi

if [ "${1:-server}" = "server" ]; then
    if [ "$#" -gt 0 ]; then
        shift
    fi
    exec java \
        -XX:MaxRAMPercentage=70.0 \
        -XX:+ExitOnOutOfMemoryError \
        -Djava.security.egd=file:/dev/urandom \
        -jar /app/app.jar \
        "$@"
fi

exec "$@"

