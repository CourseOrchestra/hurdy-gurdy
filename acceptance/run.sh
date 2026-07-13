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
# => 2*3*3 (java) + 2*3 (kotlin) = 24 cases.
#
# The --response-parameter / --force-snake-case toggles are left at their
# defaults here; their effect on output is covered thoroughly by the JUnit
# tests, and multiplying the packaging matrix by them adds no packaging
# coverage.
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
java_dto=(lombok pojo records)

# Build the case list: "name|ext|args". ext is the expected source extension.
build_cases() {
    for fw in "${frameworks[@]}"; do
        for r in "${roles[@]}"; do
            for d in "${java_dto[@]}"; do
                printf '%s|java|%s\n' \
                    "$fw-java-$r-$d" \
                    "-l java -f $fw -g $r --java-dto-style $d"
            done
            printf '%s|kt|%s\n' \
                "$fw-kotlin-$r" \
                "-l kotlin -f $fw -g $r"
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

    # Exit 0 is not enough: a swallowed error can print a stack trace and still
    # let the process succeed. Fail if the (combined) output mentions Exception.
    if grep -q 'Exception' "$work/stdout"; then
        echo "FAIL: $name (Exception in output despite exit 0)" >&2
        sed 's/^/    /' "$work/stdout" >&2
        fail=$((fail + 1))
        continue
    fi

    pass=$((pass + 1))
done < <(build_cases)

echo "acceptance: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
