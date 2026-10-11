#!/bin/sh
# Stand-in for bubblewrap: user namespaces are unavailable under proot, so
# drop the sandbox options and run the command directly (glycin, flatpak-ish tools).
while [ $# -gt 0 ]; do
  case "$1" in
    --) shift; break ;;
    --chdir) cd "$2" 2>/dev/null; shift 2 ;;
    --bind|--bind-try|--ro-bind|--ro-bind-try|--dev-bind|--dev-bind-try|--symlink|--setenv|\
    --file|--bind-data|--ro-bind-data|--chmod) shift 3 ;;
    --dev|--proc|--tmpfs|--mqueue|--dir|--remount-ro|--unsetenv|--seccomp|--add-seccomp-fd|\
    --info-fd|--json-status-fd|--sync-fd|--block-fd|--userns-block-fd|--uid|--gid|--hostname|\
    --perms|--size|--args|--userns|--userns2|--pidns|--cap-add|--cap-drop|--exec-label|\
    --file-label|--lock-file|--overlay-src|--argv0) shift 2 ;;
    --*) shift ;;
    *) break ;;
  esac
done
exec "$@"
