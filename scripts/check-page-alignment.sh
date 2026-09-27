#!/usr/bin/env bash
# Fails when any native library inside the given APKs or bundles has a LOAD
# segment aligned below 16 KB, which Play refuses at targetSdk 35 and later, or
# one whose address and file offset differ by other than a multiple of 16 KB,
# which a 16 KB kernel cannot map. The programs shipped as lib*.so, the static
# EXEC loaders among them, are checked the same way.
#
#   bash scripts/check-page-alignment.sh app.apk app.aab ...
#
# READELF names the llvm-readelf to use; the default is the NDK's.
set -euo pipefail

if [ $# -eq 0 ]; then
    echo "usage: $0 <apk-or-aab>..." >&2
    exit 2
fi

READELF=${READELF:-$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf}
MIN=$((0x4000))

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

bad=0
checked=0
printf '%-8s %-10s %s\n' RESULT MIN_ALIGN LIBRARY
n=0
for archive in "$@"; do
    n=$((n + 1))
    dest="$WORK/$n"
    mkdir -p "$dest"
    # An APK keeps its libraries under lib/<abi>/, a bundle under
    # <module>/lib/<abi>/. unzip exits 11 when nothing matches.
    case "$archive" in
        *.aab) pattern='*/lib/*/*.so' ;;
        *) pattern='lib/*/*.so' ;;
    esac
    unzip -q -o -d "$dest" "$archive" "$pattern" || [ $? -eq 11 ]
    while IFS= read -r so; do
        checked=$((checked + 1))
        # Align is the last column of a LOAD line; the flags column may hold a
        # space, so the fields before it do not count reliably. Offset and
        # VirtAddr come first. An EXEC is mapped at its link addresses, so
        # every LOAD's VirtAddr - Offset is checked against 16 KB as well.
        min=
        misplaced=
        while read -r offset vaddr a; do
            a=$((a))
            if [ -z "$min" ] || [ "$a" -lt "$min" ]; then min=$a; fi
            [ $(( (vaddr - offset) % MIN )) -eq 0 ] || misplaced=1
        done < <("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $2, $3, $NF }')
        if [ -z "$min" ]; then
            result=FAIL; shown=none
        else
            shown=$(printf '0x%x' "$min")
            if [ "$min" -lt "$MIN" ] || [ -n "$misplaced" ]; then result=FAIL; else result=ok; fi
        fi
        [ "$result" = ok ] || bad=$((bad + 1))
        printf '%-8s %-10s %s!%s\n' "$result" "$shown" "$archive" "${so#"$dest"/}"
    done < <(find "$dest" -type f -name '*.so' | sort)
done

if [ "$checked" -eq 0 ]; then
    echo "no native libraries found in $*" >&2
    exit 1
fi
if [ "$bad" -gt 0 ]; then
    echo "$bad of $checked libraries have a LOAD segment aligned below 0x4000 or placed off it" >&2
    exit 1
fi
echo "all $checked libraries are aligned to 0x4000 or more"
