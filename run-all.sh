#!/usr/bin/env sh
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$script_dir/scripts/run-all.sh" "$@"

