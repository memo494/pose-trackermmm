#!/usr/bin/env bash
# scripts/cloud-setup.sh — Android SDK setup for pose-tracker-android cloud sessions.
#
# Installs the Android SDK under $HOME/android-sdk (cmdline-tools, platform-tools,
# the platform and build-tools matching compileSdk), accepts licences, exports
# ANDROID_HOME, and configures the Gradle/JVM proxy from $HTTPS_PROXY so Gradle can
# reach dl.google.com and Maven Central through the cloud session's HTTPS proxy.
#
# Idempotent: safe to re-run. Designed to finish in a few minutes.
# Paste the body of this file into the cloud environment's "Setup script" field
# (see docs/BUILD_PLAN.md §1.3), or run it once per fresh session:  bash scripts/cloud-setup.sh
set -euo pipefail

# --- Versions (bump deliberately; keep in sync with gradle/libs.versions.toml) --------------
# cmdline-tools build number from https://dl.google.com/android/repository/repository2-3.xml
CMDLINE_TOOLS_BUILD="16111833"   # looked up 2026-11-10 (latest linux _latest.zip)
COMPILE_SDK="36"                 # Android 16: required by current AndroidX (see gradle/libs.versions.toml)
BUILD_TOOLS="36.0.0"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
echo "==> ANDROID_HOME=$ANDROID_HOME"
mkdir -p "$ANDROID_HOME"

# --- 1. Proxy: cloud sessions route all HTTPS through a local agent proxy -------------------
if [ -n "${HTTPS_PROXY:-}" ]; then
  _p="${HTTPS_PROXY#http://}"; _p="${_p#https://}"; _p="${_p%/}"
  PROXY_HOST="${_p%%:*}"; PROXY_PORT="${_p##*:}"
  echo "==> Configuring Gradle/JVM proxy: $PROXY_HOST:$PROXY_PORT"
  mkdir -p "$HOME/.gradle"
  cat > "$HOME/.gradle/gradle.properties" <<EOF
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.caching=true
EOF
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT -Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT"
fi

# --- 2. cmdline-tools -----------------------------------------------------------------------
SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMGR" ]; then
  tmp="$(mktemp -d)"
  url="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  echo "==> Downloading cmdline-tools: $url"
  curl -fSL --retry 3 --retry-delay 2 -o "$tmp/cmdline-tools.zip" "$url"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# --- 3. Licences + packages -----------------------------------------------------------------
echo "==> Accepting licences"
yes | "$SDKMGR" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
echo "==> Installing platform-tools, platforms;android-${COMPILE_SDK}, build-tools;${BUILD_TOOLS}"
"$SDKMGR" --sdk_root="$ANDROID_HOME" \
  "platform-tools" \
  "platforms;android-${COMPILE_SDK}" \
  "build-tools;${BUILD_TOOLS}"

# --- 4. Persist ANDROID_HOME for future shells ---------------------------------------------
# On the cloud environment's "Setup script" this makes ANDROID_HOME available to every later
# shell. Set CLOUD_SETUP_PERSIST=0 to skip (e.g. local runs that must not touch the profile).
if [ "${CLOUD_SETUP_PERSIST:-1}" != "0" ] && ! grep -q 'android-sdk' "$HOME/.bashrc" 2>/dev/null; then
  {
    echo ""
    echo "# Android SDK (added by scripts/cloud-setup.sh)"
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export PATH=\"\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH\""
  } >> "$HOME/.bashrc"
fi

# --- 5. local.properties for the project (gitignored; Gradle reads sdk.dir) -----------------
_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$_root/settings.gradle.kts" ] || [ -f "$_root/settings.gradle" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$_root/local.properties"
  echo "==> Wrote $_root/local.properties"
fi

echo "==> Android SDK ready at $ANDROID_HOME"
echo "    export ANDROID_HOME=$ANDROID_HOME"
