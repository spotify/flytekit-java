#!/bin/bash

set -u -e -o pipefail

DIR="$(dirname $0)/../"
pushd "$DIR"

if [ -e ".env.local" ]; then
    source ".env.local"
fi

if [[ -z "${DOCKER_REPOSITORY:-}" ]]; then
    echo "!!! Using default Docker repository: docker.io/flyte"
    echo "!!! You can override it with DOCKER_REPOSITORY env. variable"
    echo ""

    DOCKER_REPOSITORY="docker.io/flyte"
fi

if [[ -z "${FLYTE_PLATFORM_URL:-}" ]]; then
    FLYTE_PLATFORM_URL="CHANGEME"
fi

if [[ -z "${FLYTE_PLATFORM_INSECURE:-}" ]]; then
    FLYTE_PLATFORM_INSECURE="True"
fi

if ! command -v mvn &> /dev/null
then
    echo "Maven is required to build base Docker image."
    echo ""
    echo "Linux:"
    echo "$ apt install maven"
    echo ""
    echo "macOS:"
    echo "$ brew install maven"

    exit 1
fi

FLYTE_INTERNAL_IMAGE="$DOCKER_REPOSITORY/flytekit-java" mvn package

BASE_IMAGE_NAME=$(cat jflyte-build/target/docker/image-name)
VERSION=$(git describe --always)
EXAMPLES_IMAGE_NAME="$DOCKER_REPOSITORY/flytekit-java-examples:$VERSION"

dockerfile=$(cat <<EOF
FROM $BASE_IMAGE_NAME

ARG FLYTE_INTERNAL_IMAGE
ENV FLYTE_INTERNAL_IMAGE=\$FLYTE_INTERNAL_IMAGE

COPY flytekit-examples/target/lib /jflyte/modules/java-tasks
EOF
)

echo "Example Dockerfile:"
echo ""
echo "$dockerfile"
echo ""

temp_dockerfile=$(mktemp)
echo "$dockerfile" > $temp_dockerfile

echo "Running:"
echo ""
echo "$ docker build -f $temp_dockerfile -t \"$EXAMPLES_IMAGE_NAME\" \"--build-arg=FLYTE_INTERNAL_IMAGE=${EXAMPLES_IMAGE_NAME}\" ."
echo ""
docker build -f "$temp_dockerfile" -t "$EXAMPLES_IMAGE_NAME" "--build-arg=FLYTE_INTERNAL_IMAGE=${EXAMPLES_IMAGE_NAME}" .

echo ""
echo "Now you can register your workflows and tasks:"
echo ""
echo "$ docker push $EXAMPLES_IMAGE_NAME"
echo "$ docker run \\
    --network=host \\
    -e FLYTE_PLATFORM_URL=$FLYTE_PLATFORM_URL \\
    -e FLYTE_PLATFORM_INSECURE=$FLYTE_PLATFORM_INSECURE \\
    $EXAMPLES_IMAGE_NAME \\
    jflyte register workflows -p=flytetester -d=development -v=$(git describe --always)"
