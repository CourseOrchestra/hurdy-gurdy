#!/usr/bin/env bash
#
# Acceptance test: drive the *packaged* hurdy-gurdy CLI (shaded jar or native
# binary) through the full parameter matrix and check, for each variant, that
# the generator exits 0 and produces non-empty source files.
#
# Unlike the JUnit tests, this exercises the assembled artifact, so it catches
# packaging regressions the unit tests cannot -- e.g. a runtime dependency
# stripped from the jar/native image (see PR #543: jackson-datatype-jsr310),
# which crashes the generator before it writes anything.
#
# The matrix is the full cartesian product of:
#   framework      : spring, quarkus
#   language       : java, kotlin
#   generate role  : controller, api, client   (each generated separately)
#   java-dto-style : lombok, pojo, records      (java only; N/A for kotlin)
#   --response-parameter / --no-response-parameter
#   --force-snake-case   / --no-force-snake-case
# => 2*3*3*2*2 (java) + 2*3*2*2 (kotlin) = 96 cases.
#
# Usage:
#   acceptance/run.sh java -jar target/hurdy-gurdy-*-cli.jar   # test the jar
#   acceptance/run.sh ./target/hurdy-gurdy                     # test the native binary
#
set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "usage: $0 <launcher> [launcher-args...]   e.g. $0 java -jar target/*-cli.jar" >&2
    exit 2
fi
LAUNCH=("$@")

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
spec="src/test/resources/sample2.yaml"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

frameworks=(spring quarkus)
roles=(controller api client)
resp=(on off)          # --response-parameter / --no-response-parameter
snake=(on off)         # --force-snake-case   / --no-force-snake-case
java_dto=(lombok pojo records)

# Build the case list: "name|ext|args". ext is the expected source extension.
build_cases() {
    for fw in "${frameworks[@]}"; do
        for r in "${roles[@]}"; do
            for rp in "${resp[@]}"; do
                [ "$rp" = on ] && rpflag="--response-parameter" || rpflag="--no-response-parameter"
                for sn in "${snake[@]}"; do
                    [ "$sn" = on ] && snflag="--force-snake-case" || snflag="--no-force-snake-case"
                    for d in "${java_dto[@]}"; do
                        printf '%s|java|%s\n' \
                            "$fw-java-$r-$d-r$rp-s$sn" \
                            "-l java -f $fw -g $r --java-dto-style $d $rpflag $snflag"
                    done
                    printf '%s|kt|%s\n' \
                        "$fw-kotlin-$r-r$rp-s$sn" \
                        "-l kotlin -f $fw -g $r $rpflag $snflag"
                done
            done
        done
    done
}

pass=0 fail=0
while IFS='|' read -r name ext args; do
    out="$work/$name"
    # shellcheck disable=SC2086
    if ! "${LAUNCH[@]}" --spec "$spec" --root-package com.example --output "$out" $args \
            > "$work/stdout" 2>&1; then
        echo "FAIL: $name (generator exited non-zero)" >&2
        sed 's/^/    /' "$work/stdout" >&2
        fail=$((fail + 1))
        continue
    fi

    # At least one source file of the expected language...
    count=0
    [ -d "$out" ] && count=$(find "$out" -type f -name "*.$ext" | wc -l)
    if [ "$count" -eq 0 ]; then
        echo "FAIL: $name (no .$ext files generated)" >&2
        fail=$((fail + 1))
        continue
    fi

    # ...and none of them empty.
    empty=$(find "$out" -type f -name "*.$ext" -empty | head -1)
    if [ -n "$empty" ]; then
        echo "FAIL: $name (empty source file: ${empty#"$out/"})" >&2
        fail=$((fail + 1))
        continue
    fi

    pass=$((pass + 1))
done < <(build_cases)

echo "acceptance: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
