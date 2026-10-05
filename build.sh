#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/sources.txt
javac --release 21 -encoding UTF-8 -d build/classes @build/sources.txt
printf 'Manifest-Version: 1.0\nMain-Class: dev.aegis.Aegis\nImplementation-Title: Aegis\nImplementation-Version: 0.5.0\n\n' > build/manifest.mf
jar --create --file build/Aegis.jar --manifest build/manifest.mf -C build/classes .
echo "[Aegis] BUILD SUCCESS: build/Aegis.jar"
