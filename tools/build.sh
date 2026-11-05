#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."

classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac --release 17 -d "$classes" src/scheduler/*.java
mkdir -p build
jar --create --file build/scheduler.jar --main-class scheduler.Main \
    -C "$classes" . -C data demo.tsv
