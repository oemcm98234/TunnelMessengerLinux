#!/usr/bin/env bash
# ============================================================================
# Сборка Linux: AppImage (по умолчанию), DEB (--deb) или портативный jar
# (--portable).
#
# СБОРКА 13 — почему падала сборка («Could not initialize SSL context /
# toDerInputStream rejects tag type 35») и как это чинится:
#   JVM не умеет читать PEM-бандл (/etc/ssl/certs/ca-certificates.crt) как
#   trustStore — только JKS/PKCS12. PEM мог попасть в JVM двумя путями, и
#   старый фикс ловил только первый:
#     1) дефолтный trustStore самой JVM — видно в `java -XshowSettings`;
#     2) уровень Gradle: systemProp.javax.net.ssl.trustStore=... или
#        org.gradle.jvmargs в gradle.properties (своём ~/.gradle/gradle.properties
#        или GRADLE_USER_HOME) — проба JVM этого НЕ видит, а демон получает.
#   Теперь cacerts JDK задаётся БЕЗУСЛОВНО через _JAVA_OPTIONS: JVM применяет
#   эти опции ПОСЛЕДНИМИ (перекрывают и командную строку, и systemProp из
#   gradle.properties) и наследует их демону Gradle и Kotlin-демону.
#   Дополнительно: если в gradle.properties задан org.gradle.java.home, именно
#   на нём будет запущен демон — клиент выравнивается на тот же JDK.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
echo "=== TunnelMessenger Desktop v15 — сборка Linux ==="

# --- 0. GRADLE_USER_HOME: окружение -> прогретый кэш (песочница) -> дефолт ---
if [ -z "${GRADLE_USER_HOME:-}" ] && [ -d /home/z/tools/gradle-home ]; then
  export GRADLE_USER_HOME=/home/z/tools/gradle-home
fi

# --- 1. JDK 17+: org.gradle.java.home -> JAVA_HOME -> типовые пути -----------
jhome() { # первый непустой org.gradle.java.home из перечисленных properties
  local f v
  for f in "$@"; do
    [ -f "$f" ] || continue
    v="$(sed -n 's/^[[:space:]]*org\.gradle\.java\.home[[:space:]]*=[[:space:]]*//p' "$f" | head -1 | tr -d '"')"
    if [ -n "$v" ]; then echo "$v"; return; fi
  done
}

JDKS=()
_GJH="$(jhome gradle.properties "${GRADLE_USER_HOME:-/nonexistent}/gradle.properties" "$HOME/.gradle/gradle.properties")"
[ -n "$_GJH" ] && JDKS+=("$_GJH")
[ -n "${JAVA_HOME:-}" ] && JDKS+=("$JAVA_HOME")
for c in /home/z/tools/jdk-* "$HOME"/.jdks/*/ /usr/lib/jvm/java-17-* /usr/lib/jvm/java-21-* \
         /usr/lib/jvm/default-java /opt/java/* /opt/jdk*; do
  [ -e "$c" ] && JDKS+=("$c")
done

JAVA_HOME=""
# Предпочитаем полноценный JDK (есть javac — нужен Kotlin-компилятору и jpackage)
for c in "${JDKS[@]:-}"; do
  [ -n "$c" ] && [ -x "$c/bin/javac" ] && JAVA_HOME="$c" && break
done
if [ -z "$JAVA_HOME" ]; then
  for c in "${JDKS[@]:-}"; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] && JAVA_HOME="$c" && break
  done
fi
if [ -z "$JAVA_HOME" ]; then
  echo "ОШИБКА: JDK 17+ не найден. Установите: sudo apt install openjdk-17-jdk" >&2; exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
command -v java >/dev/null 2>&1 || { echo "ОШИБКА: java недоступен" >&2; exit 1; }
[ -x "$JAVA_HOME/bin/javac" ] || echo "ПРЕДУПРЕЖДЕНИЕ: в JAVA_HOME нет javac — это JRE, сборка может не пройти" >&2
echo "Java: $(java -version 2>&1 | head -1)"
echo "JAVA_HOME: $JAVA_HOME"
[ -n "$_GJH" ] && echo "org.gradle.java.home из gradle.properties: $_GJH"

# --- 2. SSL-фикс trustStore — БЕЗУСЛОВНО --------------------------------------
# Подменяем trustStore на штатный cacerts ЭТОГО JDK всегда (не только когда
# проба показала PEM: проба не видит systemProp из gradle.properties).
CAC="$JAVA_HOME/lib/security/cacerts"
[ -f "$CAC" ] || CAC="$JAVA_HOME/jre/lib/security/cacerts"
if [ -f "$CAC" ] && ! head -c 15 "$CAC" | grep -q -- "-----BEGIN"; then
  export _JAVA_OPTIONS="${_JAVA_OPTIONS:-} -Djavax.net.ssl.trustStore=$CAC -Djavax.net.ssl.trustStoreType=JKS -Djavax.net.ssl.trustStorePassword=changeit"
  echo "SSL-фикс: _JAVA_OPTIONS += -Djavax.net.ssl.trustStore=$CAC (JKS)"
  echo "          перекрывает PEM/systemProp (в т.ч. из ~/.gradle/gradle.properties), наследуется демоном Gradle"
else
  echo "ПРЕДУПРЕЖДЕНИЕ: cacerts JDK не найден — при ошибке SSL проверьте javax.net.ssl.trustStore в gradle.properties" >&2
fi

# --- 3. Сборка ----------------------------------------------------------------
if [ ! -x ./gradlew ]; then chmod +x ./gradlew; fi
# Демоны, запущенные с прежними _JAVA_OPTIONS/проперти, не переиспользуем
./gradlew --stop >/dev/null 2>&1 || true

case "${1:-}" in
  --deb)
    ./gradlew packageDeb
    echo "=== Готово (DEB) ==="
    find build/compose/binaries/main-deb -name "*.deb" -printf "  %p  [%s байт]\n" 2>/dev/null || true
    ;;
  --portable)
    ./gradlew fatJarLinux64
    bash scripts/package-portable.sh linux64
    echo "=== Готово (портативный zip) ==="
    find dist -name "TunnelMessengerDesktop_v15_linux64.zip" -printf "  %p  [%s байт]\n" 2>/dev/null || true
    echo "  Запуск: java -jar prebuilt/TunnelMessengerDesktop-15.0-linux64.jar"
    ;;
  "")
    ./gradlew packageAppImage
    echo "=== Готово (AppImage) ==="
    find build/compose/binaries/main-app -maxdepth 3 -type d -name "TunnelMessenger*" -printf "  %p\n" 2>/dev/null || true
    du -sh build/compose/binaries/main-app/*/* 2>/dev/null | head -2 || true
    echo "  Портативный запуск (без установки): java -jar prebuilt/TunnelMessengerDesktop-15.0-linux64.jar (сначала: ./gradlew fatJarLinux64)"
    ;;
  *)
    echo "Использование: $0 [--deb|--portable]" >&2; exit 1;;
esac
