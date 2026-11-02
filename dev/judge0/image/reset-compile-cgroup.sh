#!/bin/bash
# Reset only the completed compilation's empty cgroup in this isolated probe.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
[[ $# -eq 1 && "$1" =~ ^(0|[1-9][0-9]{0,2})$ && $EUID -eq 0 ]] || exit 1
root=/sys/fs/cgroup/codelobby-runner-judge0-v1
[[ $(cat /run/isolate/cgroup) == "$root" ]] || exit 1
[[ -d "$root" && ! -L "$root" && $(stat -c %u "$root") == 0 ]] || exit 1
group=$root/box-$1
[[ -d "$group" && ! -L "$group" && -f "$group/cgroup.procs" ]] || exit 1
[[ -z $(cat "$group/cgroup.procs") ]] || exit 1
# rmdir also fails if child cgroups exist. No recursive deletion or cgroup.kill.
rmdir "$group"
mkdir "$group"
