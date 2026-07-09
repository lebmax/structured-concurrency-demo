#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

. "$script_dir/java-env.sh"

release="${RELEASE:-26}"
javac_bin=$(scdemo_find_tool javac)
source_root="$project_root/src/main/java"
build_dir="$project_root/build/classes"
argfile="$project_root/build/sources.list"

if [ ! -d "$source_root" ]; then
    printf 'No Java source directory found: %s\n' "$source_root" >&2
    exit 1
fi

mkdir -p "$build_dir"
: > "$argfile"

find "$source_root" -name '*.java' -type f | sort | while IFS= read -r source_file; do
    scdemo_quote_argfile_line "$(scdemo_tool_path "$javac_bin" "$source_file")" >> "$argfile"
done

if [ ! -s "$argfile" ]; then
    printf 'No Java sources found under %s\n' "$source_root" >&2
    exit 1
fi

build_dir_for_tool=$(scdemo_tool_path "$javac_bin" "$build_dir")
argfile_for_tool=$(scdemo_tool_path "$javac_bin" "$argfile")

"$javac_bin" --enable-preview --release "$release" -encoding UTF-8 -d "$build_dir_for_tool" @"$argfile_for_tool"

printf 'Compiled to %s\n' "$build_dir"

