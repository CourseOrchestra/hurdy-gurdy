# Acceptance tests

End-to-end checks that drive the **packaged** CLI (shaded jar or native
binary) through the full parameter matrix and, for each variant, assert the
generator **exits 0** and writes **non-empty** source files.

These complement the JUnit tests: they run the assembled artifact, so they
catch packaging regressions the unit tests cannot — e.g. a runtime dependency
stripped from the jar/native image (see PR #543, `jackson-datatype-jsr310`),
which crashes the generator before it writes anything.

The matrix is the full cartesian product of framework (spring/quarkus),
language (java/kotlin), `--generate` role (controller/api/client, each run
separately), and java DTO style (lombok/pojo/records, java only) — 24 cases.
The `--response-parameter` / `--force-snake-case` toggles are left at their
defaults (their output is covered by the JUnit tests). The case list is
generated in `run.sh`; no golden files are checked in.

## Run locally

```bash
mvn -DskipTests package                                   # build the jar
acceptance/run.sh java -jar "$(ls target/hurdy-gurdy-*-cli.jar)"
```

Or against a native binary:

```bash
mvn -Pnative -DskipTests package
acceptance/run.sh ./target/hurdy-gurdy
```

CI runs the same script in the `build` (PRs) and `release` workflows.
