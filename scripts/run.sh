#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

. "$script_dir/java-env.sh"

scenario="${1:-all}"
java_bin=$(scdemo_find_tool java)

"$script_dir/compile.sh"

classpath=$(scdemo_tool_path "$java_bin" "$project_root/build/classes")
"$java_bin" --enable-preview -cp "$classpath" dev.axiom.scdemo.StructuredConcurrencyDemo "$scenario"

