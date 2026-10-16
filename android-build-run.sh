#!/usr/bin/env bash
# Build and deploy only the selected Android ABI; preserve runtime state.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="${ANDROID_DIR:-/data/local/tmp/airc}"
PORT="${PORT:-9876}"
SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
CONNECT=""
PREFER_ROOT=true
TRY_ADB_ROOT=false
usage() {
  cat <<'HELP'
Usage: ./android-build-run.sh [--serial SERIAL | --connect IP:PORT] [--port PORT] [--android-dir PATH] [--root | --no-root] [--adb-root]
Build, push and start airc-runtime in the background. Auto-detect root adbd / su by default.
--no-root runs as shell; --adb-root first attempts to restart adbd as root.
Automatically detects arm64-v8a, armeabi-v7a or x86_64 (Android API 26+).
Environment: ADB_SERIAL / ANDROID_SERIAL, ANDROID_DIR, PORT and build-runtime.py's NDK variables.
HELP
}
die() { echo "error: $*" >&2; exit 1; }
while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --root) PREFER_ROOT=true; shift ;;
    --no-root) PREFER_ROOT=false; shift ;;
    --adb-root) TRY_ADB_ROOT=true; shift ;;
    --serial|--connect|--port|--android-dir)
      if (($# < 2)) || [[ -z "$2" ]]; then die "missing value for $1"; fi
      case "$1" in
        --serial) SERIAL="$2" ;;
        --connect) CONNECT="$2" ;;
        --port) PORT="$2" ;;
        --android-dir) ANDROID_DIR="$2" ;;
      esac
      shift 2 ;;
    *) die "unknown option: $1" ;;
  esac
done
[[ "$ANDROID_DIR" =~ ^/[A-Za-z0-9._/-]+$ && -n "${ANDROID_DIR//\//}" && ! "$ANDROID_DIR" =~ (^|/)\.\.?(/|$) ]] || die "ANDROID_DIR must be an absolute path without '..' or shell characters"
[[ "$PORT" =~ ^[0-9]{1,5}$ ]] || die "PORT must be 1..65535"
PORT=$((10#$PORT))
((PORT >= 1 && PORT <= 65535)) || die "PORT must be 1..65535"
for tool in adb python3 cargo rustup; do command -v "$tool" >/dev/null || die "$tool is not in PATH"; done
if [[ -n "$CONNECT" ]]; then
  [[ -z "$SERIAL" || "$SERIAL" == "$CONNECT" ]] || die "--connect conflicts with selected serial"
  adb connect "$CONNECT"
  SERIAL="$CONNECT"
fi
collect_devices() {
  DEVICES=()
  while IFS=$'\t' read -r serial state; do
    [[ "$state" != device ]] || DEVICES+=("$serial")
  done < <(adb devices | tr -d '\r')
}
if [[ -z "$SERIAL" ]]; then
  collect_devices
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
if [[ "$TRY_ADB_ROOT" == true ]]; then
  "${ADB[@]}" root || true
  "${ADB[@]}" wait-for-device
fi
shell_quote() { printf "'%s'" "${1//\'/\'\\\'\'}"; }
MANAGER=shell
ADB_UID="$("${ADB[@]}" shell id -u | tr -d '\r')"
[[ "$ADB_UID" == 0 || "$ADB_UID" == 2000 ]] || die "ADB user must be root or shell"
SU_STYLE='command'
PREVIOUS_ROOT="$("${ADB[@]}" shell "if [ -e '$ANDROID_DIR/.root-mode' ] || { [ -d '$ANDROID_DIR/state' ] && [ ! -r '$ANDROID_DIR/state' ]; }; then echo root; fi" | tr -d '\r')"
if [[ "$ADB_UID" == 0 ]]; then
  MANAGER=root-adbd
elif [[ "$PREFER_ROOT" == true || "$PREVIOUS_ROOT" == root ]]; then
  if [[ "$("${ADB[@]}" shell "su -c 'id -u' 2>/dev/null" | tr -d '\r' || true)" == 0 ]]; then MANAGER=su
  elif [[ "$("${ADB[@]}" shell "su 0 sh -c 'id -u' 2>/dev/null" | tr -d '\r' || true)" == 0 ]]; then MANAGER=su; SU_STYLE=uid_shell; fi
fi
[[ "$PREVIOUS_ROOT" != root || "$MANAGER" != shell ]] || die "existing runtime uses root; restore su authorization before managing it"
NEW_ROOT=0
PRIVILEGED=0
if [[ "$MANAGER" != shell ]]; then
  PRIVILEGED=1
  if [[ "$PREFER_ROOT" == true ]]; then NEW_ROOT=1; fi
fi
remote() {
  local command="$1"
  if [[ "$MANAGER" == su ]]; then
    if [[ "$SU_STYLE" == uid_shell ]]; then command="su 0 sh -c $(shell_quote "$command")"; else command="su -c $(shell_quote "$command")"; fi
  fi
  "${ADB[@]}" shell "$command"
}
if [[ "$PRIVILEGED" == 1 && "$NEW_ROOT" == 0 ]]; then
  if [[ "$(remote "su 2000 -c 'id -u' 2>/dev/null" | tr -d '\r' || true)" != 2000 ]] && [[ "$(remote "su 2000 sh -c 'id -u' 2>/dev/null" | tr -d '\r' || true)" != 2000 ]]; then
    die "cannot switch root adbd/su to shell UID; check su support or use adb unroot"
  fi
fi
if [[ "$NEW_ROOT" == 1 ]]; then echo "Runtime permissions: root ($MANAGER)"; else echo "Runtime permissions: ADB shell (root unavailable or disabled)"; fi
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$SDK" =~ ^[0-9]+$ ]] || ((SDK < 26)); then die "Android API 26+ required"; fi
ABILIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
[[ -n "$ABILIST" ]] || ABILIST="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
case ",$ABILIST," in
  *,x86_64,*) ABI=x86_64 ;;
  *,arm64-v8a,*) ABI=arm64-v8a ;;
  *,armeabi-v7a,*) ABI=armeabi-v7a ;;
  *) die "unsupported ABI: $ABILIST" ;;
esac
echo "Device: $SERIAL; ABI: $ABI; port: $PORT"
python3 "$PROJECT_DIR/scripts/build-runtime.py" --abi "$ABI"
BINARY="$ANDROID_DIR/airc-runtime"
PREPARE="[ ! -L '$ANDROID_DIR' ] && [ ! -L '$ANDROID_DIR/state' ] && [ ! -L '$BINARY' ] && [ ! -L '$BINARY.next' ] && mkdir -p '$ANDROID_DIR' && chmod 700 '$ANDROID_DIR'"
if [[ "$PRIVILEGED" == 1 ]]; then PREPARE+=" && chown -h 2000:2000 '$ANDROID_DIR'"; fi
remote "$PREPARE && rm -f '$BINARY.next'"
"${ADB[@]}" push "$PROJECT_DIR/androidApp/src/main/assets/runtime/airc-runtime-$ABI" "$BINARY.next"
remote "chmod 700 '$BINARY.next' && '$BINARY.next' --version"
# Use Android sh, and stop the owned service BEFORE renaming its executable.
# All substituted paths have been validated above; state/config are never deleted.
remote "sh -s -- '$BINARY' '$ANDROID_DIR/state' '$PORT' '$NEW_ROOT' '$PRIVILEGED' '$SU_STYLE'" <<'REMOTE'
binary=$1
state=$2
port=$3
directory=${binary%/*}
# Both the top directory and state must be real directories.
[ ! -L $directory ] && [ ! -L $directory/state ] && [ ! -L $binary ] && [ ! -L $binary.next ] || exit 1
chmod 700 $binary.next || exit 1
reply=$($binary.next service check-port --state-dir $directory/state --port $port 2>&1)
if [ $? != 0 ]; then
    echo "部署前检查失败：请检查端口 $port 是否被残留服务占用；root 进程需使用 root 停止，请勿删除状态目录。原服务与程序未替换。"
    printf '%s\n' "$reply"
    echo airc-preflight-failed
    exit 1
fi
su_style=$6
verify_shell() {
    if [ "$(su 2000 -c 'id -u' 2>/dev/null)" = 2000 ]; then su_style=command; return 0; fi
    if [ "$(su 2000 sh -c 'id -u' 2>/dev/null)" = 2000 ]; then su_style=uid_shell; return 0; fi
    return 1
}
as_shell() {
    if [ "$su_style" = uid_shell ]; then su 2000 sh -c "$1"; else su 2000 -c "$1"; fi
}
new_root=$4
if [ "$new_root" = 0 ] && [ $5 = 1 ]; then
    verify_shell || { echo '无法切换到 shell 用户，请检查目标 su 支持'; exit 1; }
fi
old_root=0
privileged=$5
normalize_shell() {
    if [ "$privileged" = 1 ]; then
        chown -h 2000:2000 $directory $binary || return 1
        if [ -d $directory/state ]; then
            chown -hR 2000:2000 $directory/state || return 1
        fi
    fi
}
start_runtime() {
    root_mode=$1
    start_port=$2
    if [ "$root_mode" = 1 ] || [ "$privileged" = 0 ]; then
        if [ "$root_mode" = 1 ] && [ -d $directory/state/platform ]; then
            [ ! -L $directory/state/platform ] && chown -hR 0:0 $directory/state/platform || return 1
        fi
        $binary service start --state-dir $directory/state --port "$start_port"
    else
        normalize_shell || return 1
        as_shell "$binary service start --state-dir $directory/state --port $start_port"
    fi
}
old_marker=0
marker_changed=0
[ ! -e $directory/.root-mode ] || old_marker=1
old_present=0
old_running=0
old_port=$port
stopped=0
swapped=0
restore() {
    trap - 0 HUP INT TERM
    restored=1
    if [ "$swapped" = 1 ]; then
        if [ -f $directory/state/service.json ]; then
            stop_binary=$binary
            if [ "$old_present" = 1 ]; then stop_binary=$binary.previous; fi
            "$stop_binary" service stop --state-dir $directory/state >/dev/null 2>&1 || restored=0
        fi
        if [ "$restored" != 1 ]; then
            echo airc-rollback-failed
            return
        fi
        if [ "$old_present" = 1 ]; then
            mv $binary.previous $binary || restored=0
        else
            rm -f $binary || restored=0
        fi
    fi
    if [ "$old_running" = 1 ] && [ "$stopped" = 1 ]; then
        reply=$(start_runtime "$old_root" "$old_port" 2>&1)
        case "$reply" in
            *'"ready":true'*) ;;
            *) restored=0; printf '%s\n' "$reply" ;;
        esac
    fi
    if [ "$restored" = 1 ] && [ "$marker_changed" = 1 ]; then
        if [ "$old_marker" = 1 ]; then
            touch $directory/.root-mode || restored=0
        else
            rm -f $directory/.root-mode || restored=0
        fi
    fi
    rm -f $binary.next
    if [ "$restored" = 1 ]; then rm -f $binary.previous; echo airc-rolled-back; else echo airc-rollback-failed; fi
}
trap restore 0
trap 'exit 1' HUP INT TERM
if [ -f $binary ]; then
    old_present=1
    reply=$($binary service status --state-dir $directory/state 2>&1) || { printf '%s\n' "$reply"; exit 1; }
    case "$reply" in
        *'"running":true'*)
            old_running=1
            old_pid=$(printf '%s\n' "$reply" | sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p')
            case "$old_pid" in ''|*[!0-9]*) exit 1 ;; esac
            old_uid=$(stat -c %u /proc/"$old_pid") || exit 1
            if [ "$old_uid" = 0 ]; then old_root=1; fi
            # Verify rollback can restore the original shell identity before stopping it.
            if [ "$privileged" = 1 ] && [ "$old_root" = 0 ]; then
                verify_shell || { echo '无法恢复原 shell 权限：目标 su 不支持切换用户'; exit 1; }
            fi
            old_port=$(printf '%s\n' "$reply" | sed -n 's/.*"port":\([0-9][0-9]*\).*/\1/p')
            case "$old_port" in ''|*[!0-9]*) exit 1 ;; esac
            ;;
    esac
    cp -p $binary $binary.previous || exit 1
    reply=$($binary service stop --state-dir $directory/state 2>&1)
    case "$reply" in
        *'"running":false'*) stopped=1 ;;
        *) printf '%s\n' "$reply"; exit 1 ;;
    esac
fi
chmod 700 $binary.next || exit 1
mv $binary.next $binary || exit 1
swapped=1
reply=$(start_runtime "$new_root" $port 2>&1)
case "$reply" in
    *'"ready":true'*) ;;
    *) printf '%s\n' "$reply"; exit 1 ;;
esac
marker_changed=1
if [ "$new_root" = 1 ]; then
    touch $directory/.root-mode || exit 1
    chown -h 2000:2000 $directory $directory/.root-mode || exit 1
else
    rm -f $directory/.root-mode || exit 1
fi
trap - 0 HUP INT TERM
rm -f $binary.previous
printf '%s\n' "$reply"
echo airc-committed
REMOTE
echo "Runtime started. LAN endpoint: ws://<device-ip>:$PORT/ws"
echo "Log: $ANDROID_DIR/state/runtime.log"
