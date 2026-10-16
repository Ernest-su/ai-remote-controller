package ernest.airc

/** One target-side transaction; traps also run if the ADB shell receives HUP. */
object RuntimeDeployment {
    fun script(directory: String, port: Int, privilege: RuntimePrivilege = RuntimePrivilege(RuntimePrivilege.Mode.SHELL, false)): String {
        require(port in 1..65535)
        require(directory.matches(Regex("/[a-zA-Z0-9_./-]+")) && directory.trim('/').isNotEmpty() && directory.split('/').none { it == ".." || it == "." })
        val binary = "$directory/airc-runtime"
        val privileged = privilege.manager != RuntimePrivilege.Mode.SHELL
        return """
            # Both the top directory and state must be real directories.
            [ ! -L $directory ] && [ ! -L $directory/state ] && [ ! -L $binary ] && [ ! -L $binary.next ] || exit 1
            chmod 700 $binary.next || exit 1
            reply=${'$'}($binary.next service check-port --state-dir $directory/state --port $port 2>&1)
            if [ ${'$'}? != 0 ]; then
                echo '部署前检查失败：请检查端口 $port 是否被残留服务占用；root 进程需使用 root 停止，请勿删除状态目录。原服务与程序未替换。'
                printf '%s\n' "${'$'}reply"
                echo airc-preflight-failed
                exit 1
            fi
            su_style=${if (privilege.suStyle == RuntimePrivilege.SuStyle.UID_SHELL) "uid_shell" else "command"}
            verify_shell() {
                if [ "${'$'}(su 2000 -c 'id -u' 2>/dev/null)" = 2000 ]; then su_style=command; return 0; fi
                if [ "${'$'}(su 2000 sh -c 'id -u' 2>/dev/null)" = 2000 ]; then su_style=uid_shell; return 0; fi
                return 1
            }
            as_shell() {
                if [ "${'$'}su_style" = uid_shell ]; then su 2000 sh -c "${'$'}1"; else su 2000 -c "${'$'}1"; fi
            }
            new_root=${if (privilege.useRoot) 1 else 0}
            if [ "${'$'}new_root" = 0 ] && [ ${if (privileged) 1 else 0} = 1 ]; then
                verify_shell || { echo '无法切换到 shell 用户，请检查目标 su 支持'; exit 1; }
            fi
            old_root=0
            privileged=${if (privileged) 1 else 0}
            normalize_shell() {
                if [ "${'$'}privileged" = 1 ]; then
                    chown -h 2000:2000 $directory $binary || return 1
                    if [ -d $directory/state ]; then
                        chown -hR 2000:2000 $directory/state || return 1
                    fi
                fi
            }
            start_runtime() {
                root_mode=${'$'}1
                start_port=${'$'}2
                if [ "${'$'}root_mode" = 1 ] || [ "${'$'}privileged" = 0 ]; then
                    if [ "${'$'}root_mode" = 1 ] && [ -d $directory/state/platform ]; then
                        [ ! -L $directory/state/platform ] && chown -hR 0:0 $directory/state/platform || return 1
                    fi
                    $binary service start --state-dir $directory/state --port "${'$'}start_port"
                else
                    normalize_shell || return 1
                    as_shell "$binary service start --state-dir $directory/state --port ${'$'}start_port"
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
                if [ "${'$'}swapped" = 1 ]; then
                    if [ -f $directory/state/service.json ]; then
                        stop_binary=$binary
                        if [ "${'$'}old_present" = 1 ]; then stop_binary=$binary.previous; fi
                        "${'$'}stop_binary" service stop --state-dir $directory/state >/dev/null 2>&1 || restored=0
                    fi
                    if [ "${'$'}restored" != 1 ]; then
                        echo airc-rollback-failed
                        return
                    fi
                    if [ "${'$'}old_present" = 1 ]; then
                        mv $binary.previous $binary || restored=0
                    else
                        rm -f $binary || restored=0
                    fi
                fi
                if [ "${'$'}old_running" = 1 ] && [ "${'$'}stopped" = 1 ]; then
                    reply=${'$'}(start_runtime "${'$'}old_root" "${'$'}old_port" 2>&1)
                    case "${'$'}reply" in
                        *'"ready":true'*) ;;
                        *) restored=0; printf '%s\n' "${'$'}reply" ;;
                    esac
                fi
                if [ "${'$'}restored" = 1 ] && [ "${'$'}marker_changed" = 1 ]; then
                    if [ "${'$'}old_marker" = 1 ]; then
                        touch $directory/.root-mode || restored=0
                    else
                        rm -f $directory/.root-mode || restored=0
                    fi
                fi
                rm -f $binary.next
                if [ "${'$'}restored" = 1 ]; then rm -f $binary.previous; echo airc-rolled-back; else echo airc-rollback-failed; fi
            }
            trap restore 0
            trap 'exit 1' HUP INT TERM
            if [ -f $binary ]; then
                old_present=1
                reply=${'$'}($binary service status --state-dir $directory/state 2>&1) || { printf '%s\n' "${'$'}reply"; exit 1; }
                case "${'$'}reply" in
                    *'"running":true'*)
                        old_running=1
                        old_pid=${'$'}(printf '%s\n' "${'$'}reply" | sed -n 's/.*"pid":\([0-9][0-9]*\).*/\1/p')
                        case "${'$'}old_pid" in ''|*[!0-9]*) exit 1 ;; esac
                        old_uid=${'$'}(stat -c %u /proc/"${'$'}old_pid") || exit 1
                        if [ "${'$'}old_uid" = 0 ]; then old_root=1; fi
                        # Verify rollback can restore the original shell identity before stopping it.
                        if [ "${'$'}privileged" = 1 ] && [ "${'$'}old_root" = 0 ]; then
                            verify_shell || { echo '无法恢复原 shell 权限：目标 su 不支持切换用户'; exit 1; }
                        fi
                        old_port=${'$'}(printf '%s\n' "${'$'}reply" | sed -n 's/.*"port":\([0-9][0-9]*\).*/\1/p')
                        case "${'$'}old_port" in ''|*[!0-9]*) exit 1 ;; esac
                        ;;
                esac
                cp -p $binary $binary.previous || exit 1
                reply=${'$'}($binary service stop --state-dir $directory/state 2>&1)
                case "${'$'}reply" in
                    *'"running":false'*) stopped=1 ;;
                    *) printf '%s\n' "${'$'}reply"; exit 1 ;;
                esac
            fi
            chmod 700 $binary.next || exit 1
            mv $binary.next $binary || exit 1
            swapped=1
            reply=${'$'}(start_runtime "${'$'}new_root" $port 2>&1)
            case "${'$'}reply" in
                *'"ready":true'*) ;;
                *) printf '%s\n' "${'$'}reply"; exit 1 ;;
            esac
            marker_changed=1
            if [ "${'$'}new_root" = 1 ]; then
                touch $directory/.root-mode || exit 1
                chown -h 2000:2000 $directory $directory/.root-mode || exit 1
            else
                rm -f $directory/.root-mode || exit 1
            fi
            trap - 0 HUP INT TERM
            rm -f $binary.previous
            printf '%s\n' "${'$'}reply"
            echo airc-committed
        """.trimIndent()
    }
}
