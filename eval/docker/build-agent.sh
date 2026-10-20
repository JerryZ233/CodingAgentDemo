#!/bin/sh
set -eu

cache="${GRADLE_USER_HOME:-/root/.gradle}"
mkdir -p "$cache"
cp -R "${GRADLE_SEED_DIR:-/opt/gradle-seed}/." "$cache/"
chmod +x gradlew

if [ "${GRADLE_BUILD_OFFLINE:-false}" = true ]; then
    distribution=$(sed -n 's#^distributionUrl=.*/\(gradle-[0-9.]*-bin\)\.zip.*#\1#p' gradle/wrapper/gradle-wrapper.properties)
    if [ -z "$distribution" ] || ! find "$cache/wrapper/dists/$distribution" -name "$distribution.zip.ok" -print -quit 2>/dev/null | grep -q .; then
        echo 'Offline Agent build requires a cached Gradle wrapper distribution.' >&2
        exit 1
    fi
fi

# A complete local cache avoids flaky Maven/TLS connections entirely.
if ./gradlew --offline --no-daemon --console=plain installDist; then
    exit 0
fi
if [ "${GRADLE_BUILD_OFFLINE:-false}" = true ]; then
    echo 'Offline Agent build failed; no online fallback requested.' >&2
    exit 1
fi

# Retry only the build, never Harbor or model calls. Cache mounts retain partial downloads.
attempt=1
while ! ./gradlew --no-daemon --console=plain installDist; do
    if [ "$attempt" -ge 3 ]; then
        echo 'Agent build failed after three online attempts.' >&2
        exit 1
    fi
    attempt=$((attempt + 1))
    echo "Retrying Agent build ($attempt/3); downloaded dependencies are retained." >&2
    sleep 3
done
