#!/bin/sh
set -eu
set +x

runtime_mode="${MEETME_RUNTIME_PROVIDER_MODE:-gemini-only}"
case "$runtime_mode" in
    gemini-only) ;;
    gemini-luna-required)
        # Same printable ASCII contract as the renderer; shell metacharacters
        # stay opaque data. A subshell keeps validation locale out of Java.
        if ! (
            LC_ALL=C
            case "${OPENAI_API_KEY:-}" in
                ''|*[![:graph:]]*) exit 1 ;;
            esac
        ); then
            printf '%s\n' 'Required Luna runtime key is missing or invalid' >&2
            exit 1
        fi
        ;;
    *)
        printf '%s\n' 'Runtime provider mode is invalid' >&2
        exit 1
        ;;
esac

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
