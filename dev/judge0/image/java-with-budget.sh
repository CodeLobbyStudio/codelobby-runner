#!/bin/bash
# Trusted profile launcher. Isolate supplies the effective aggregate memory budget.
set -euo pipefail
mode=${1:-}
shift || exit 2
case "$mode" in
  java) reserve_mib=48; metaspace_mib=32 ;;
  javac) reserve_mib=64; metaspace_mib=64 ;;
  *) echo 'Unsupported Java launcher mode' >&2; exit 2 ;;
esac
limit=${CODELOBBY_MEMORY_LIMIT_KIB:-}
[[ "$limit" =~ ^[1-9][0-9]{0,6}$ ]] || { echo 'Missing trusted Java memory budget' >&2; exit 2; }
# Leave space for JVM native memory, code cache and class metadata.
# Very small budgets can still be insufficient to start the JVM. The cgroup is authoritative.
heap_mib=$((limit / 1024 - reserve_mib))
((heap_mib >= 8)) || heap_mib=8
flags=(-Xms8m "-Xmx${heap_mib}m" -Xss256k -XX:+UseSerialGC -XX:ActiveProcessorCount=1
       "-XX:MaxMetaspaceSize=${metaspace_mib}m" -XX:CompressedClassSpaceSize=16m
       -XX:ReservedCodeCacheSize=16m -XX:-UsePerfData)
# Keep most heap available for long-lived arrays rather than reserving a large young generation.
if [[ "$mode" == java ]]; then flags+=(-XX:MaxNewSize=4m); fi
# Do not inherit user-controlled JVM option injection variables.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
if [[ "$mode" == javac ]]; then
  compiler_flags=()
  for flag in "${flags[@]}"; do compiler_flags+=("-J$flag"); done
  exec /usr/local/codelobby/jdk21/bin/javac "${compiler_flags[@]}" "$@"
fi
exec /usr/local/codelobby/jdk21/bin/java "${flags[@]}" "$@"
