#!/usr/bin/env bash
# Lance l'API en local avec le JDK 21 de Homebrew (brew install openjdk@21) et la base MariaDB du .env.
# Usage : ./scripts/run-dev.sh            (données réelles)
#         ./scripts/run-dev.sh demo       (ajoute le jeu de démo si la base est vide)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_21:-/opt/homebrew/opt/openjdk@21}"
PROFILES="dev${1:+,$1}"
exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles="$PROFILES"
