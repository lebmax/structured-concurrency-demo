#!/usr/bin/env sh

scdemo_find_tool() {
    tool_name="$1"

    for home in \
        "${JDK_HOME:-}" \
        "${JAVA_HOME:-}" \
        "/mnt/c/Program Files/Axiom/AxiomJDK-26" \
        "/c/Program Files/Axiom/AxiomJDK-26" \
        "C:/Program Files/Axiom/AxiomJDK-26"
    do
        if [ -z "$home" ]; then
            continue
        fi

        if [ -x "$home/bin/$tool_name" ]; then
            printf '%s\n' "$home/bin/$tool_name"
            return 0
        fi

        if [ -x "$home/bin/$tool_name.exe" ]; then
            printf '%s\n' "$home/bin/$tool_name.exe"
            return 0
        fi
    done

    if command -v "$tool_name" >/dev/null 2>&1; then
        command -v "$tool_name"
        return 0
    fi

    printf '%s\n' "$tool_name not found. Set JDK_HOME to a JDK 26 directory." >&2
    return 1
}

scdemo_tool_path() {
    tool_path="$1"
    source_path="$2"

    case "$tool_path" in
        *.exe)
            if command -v cygpath >/dev/null 2>&1; then
                cygpath -m "$source_path"
                return 0
            fi

            if command -v wslpath >/dev/null 2>&1; then
                wslpath -m "$source_path"
                return 0
            fi
            ;;
    esac

    printf '%s\n' "$source_path"
}

scdemo_quote_argfile_line() {
    printf '"%s"\n' "$1"
}
