#!/usr/bin/env bash
# Build and install the debug APK on the selected Android device; preserve app data.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$PROJECT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
BUILD_APK=true
SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
CONNECT=""

usage() {
  cat <<'HELP'
Usage: ./android-install-apk.sh [--apk PATH] [--serial SERIAL | --connect IP:PORT]
Build the debug APK, then install with adb install -r, preserving existing app data.
--apk PATH installs an existing APK instead of building.
Default APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk (relative to this script).
One online device is selected automatically; multiple devices prompt for a selection.
With no online devices, prompts for an Android device IP:port.
Non-interactive use requires --serial or --connect unless exactly one device is online.
Environment: ADB_SERIAL / ANDROID_SERIAL.
This script does not launch the app.
HELP
}
die() { echo "error: $*" >&2; exit 1; }

while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --apk|--serial|--connect)
      if (($# < 2)) || [[ -z "$2" ]]; then die "missing value for $1"; fi
      case "$1" in
        --apk) APK="$2"; BUILD_APK=false ;;
        --serial) SERIAL="$2" ;;
        --connect) CONNECT="$2" ;;
      esac
      shift 2 ;;
    *) die "unknown option: $1" ;;
  esac
done

if [[ "$BUILD_APK" == false ]]; then
  [[ -f "$APK" && -r "$APK" ]] || die "APK is not a readable file: $APK"
fi
command -v adb >/dev/null || die "adb is not in PATH"

if [[ -n "$CONNECT" ]]; then
  [[ -z "$SERIAL" || "$SERIAL" == "$CONNECT" ]] || die "--connect conflicts with selected serial"
  adb connect "$CONNECT"
  SERIAL="$CONNECT"
fi

if [[ -z "$SERIAL" ]]; then
  DEVICE_LIST="$(adb devices)" || die "could not list ADB devices"
  DEVICES=()
  while IFS=$'\t' read -r serial state; do
    [[ "$state" != device ]] || DEVICES+=("$serial")
  done < <(printf '%s\n' "$DEVICE_LIST" | tr -d '\r')
  if ((${#DEVICES[@]} == 0)); then
    [[ -t 0 ]] || die "no device; use --connect IP:PORT or --serial SERIAL"
    read -r -p "Android device IP:port: " CONNECT
    [[ -n "$CONNECT" ]] || die "no device address entered"
    adb connect "$CONNECT"
    SERIAL="$CONNECT"
  elif ((${#DEVICES[@]} == 1)); then
    SERIAL="${DEVICES[0]}"
  else
    [[ -t 0 ]] || die "multiple devices; use --serial SERIAL"
    for index in "${!DEVICES[@]}"; do printf '  %d. %s\n' "$((index + 1))" "${DEVICES[index]}"; done
    read -r -p "Device number: " choice
    [[ "$choice" =~ ^[1-9][0-9]{0,3}$ ]] || die "invalid device number"
    ((choice <= ${#DEVICES[@]})) || die "device number out of range"
    SERIAL="${DEVICES[choice - 1]}"
  fi
fi

ADB=(adb -s "$SERIAL")
[[ "$("${ADB[@]}" get-state | tr -d '\r')" == device ]] || die "device is not online: $SERIAL"
echo "Device: $SERIAL"
if [[ "$BUILD_APK" == true ]]; then
  echo "Building debug APK..."
  (cd "$PROJECT_DIR" && ./gradlew :androidApp:assembleDebug --no-daemon)
fi
[[ -f "$APK" && -r "$APK" ]] || die "APK is not a readable file: $APK"
# Resolve relative paths before passing them to adb, including names beginning with '-'.
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
echo "APK: $APK"
"${ADB[@]}" install -r "$APK"
echo "APK installed on $SERIAL"
