const assert = require("assert");
const g = require("./generator.js");

// The version is fetched at page load in the browser; here we pin a concrete one
// so the snippet assertions exercise a real version string flowing through.
assert.strictEqual(g.VERSION, g.VERSION_FALLBACK, "version starts at the fallback placeholder");
g.setVersion("2.10");
assert.strictEqual(g.VERSION, "2.10", "setVersion updates the version snippets emit");

const base = {
  rootPackage: "com.example.project",
  spec: "src/main/openapi/api.yaml",
  outputDir: "build/generated-sources",
  language: "java",
  framework: "spring",
  generate: ["controller"],
  responseParameter: false,
  forceSnakeCase: true,
};

// --- Maven defaults ---
{
  const out = g.mavenSnippet(base);
  assert.ok(out.includes("<groupId>ru.curs</groupId>"), "maven groupId");
  assert.ok(out.includes("<artifactId>hurdy-gurdy</artifactId>"), "maven artifactId");
  assert.ok(out.includes(`<version>${g.VERSION}</version>`), "maven version from VERSION");
  assert.ok(out.includes("<rootPackage>com.example.project</rootPackage>"), "maven rootPackage");
  assert.ok(out.includes("<spec>src/main/openapi/api.yaml</spec>"), "maven spec");
  assert.ok(out.includes("<goal>gen-server</goal>"), "maven goal");
  assert.ok(!out.includes("<language>"), "maven omits default language");
  assert.ok(!out.includes("<framework>"), "maven omits default framework");
  assert.ok(!out.includes("<generate>"), "maven omits default generate");
  assert.ok(!out.includes("generateResponseParameter"), "maven omits default responseParameter");
  assert.ok(!out.includes("forceSnakeCaseForProperties"), "maven omits default forceSnakeCase");
  assert.ok(!out.includes("kotlin-maven-plugin"), "maven java omits the kotlin-maven-plugin note");
}
// --- Maven non-defaults ---
{
  const out = g.mavenSnippet({ ...base, language: "kotlin", framework: "quarkus",
    generate: ["controller", "client"], responseParameter: true, forceSnakeCase: false });
  assert.ok(out.includes("<language>kotlin</language>"), "maven language emitted");
  assert.ok(out.includes("<framework>quarkus</framework>"), "maven framework emitted");
  assert.ok(out.includes("<generate>controller,client</generate>"), "maven generate emitted");
  assert.ok(out.includes("<generateResponseParameter>true</generateResponseParameter>"), "maven resp param");
  assert.ok(out.includes("<forceSnakeCaseForProperties>false</forceSnakeCaseForProperties>"), "maven snake");
  assert.ok(out.includes("kotlin-maven-plugin"), "maven kotlin snippet notes the kotlin-maven-plugin requirement");
}
// --- Gradle defaults ---
{
  const out = g.gradleSnippet(base);
  assert.ok(out.includes(`id("ru.curs.hurdy-gurdy") version "${g.VERSION}"`), "gradle plugin id+version");
  assert.ok(out.includes("hurdyGurdy {"), "gradle extension block");
  assert.ok(out.includes(`"api" {`), "gradle named block");
  assert.ok(out.includes(`spec = file("src/main/openapi/api.yaml")`), "gradle spec");
  assert.ok(out.includes(`rootPackage = "com.example.project"`), "gradle rootPackage");
  assert.ok(!out.includes("Framework"), "gradle omits default framework + import");
  assert.ok(!out.includes("Role"), "gradle omits default generate + import");
  assert.ok(!out.includes("Language"), "gradle omits default language + import");
}
// --- Gradle spec block named after the OpenAPI file ---
{
  assert.ok(g.gradleSnippet({ ...base, spec: "openapi/petstore.yaml" }).includes(`"petstore" {`), "gradle block from filename");
  assert.ok(g.gradleSnippet({ ...base, spec: "billing.yml?ref=main" }).includes(`"billing" {`), "gradle block strips query+ext");
}
// --- Gradle non-defaults ---
{
  const out = g.gradleSnippet({ ...base, language: "kotlin", framework: "quarkus",
    generate: ["controller", "client"], responseParameter: true, forceSnakeCase: false });
  assert.ok(out.includes("import ru.curs.hurdygurdy.Framework"), "gradle imports Framework");
  assert.ok(out.includes("import ru.curs.hurdygurdy.Role"), "gradle imports Role");
  assert.ok(out.includes("import ru.curs.hurdygurdy.gradle.Language"), "gradle imports Language");
  assert.ok(out.includes("framework = Framework.QUARKUS"), "gradle framework enum");
  assert.ok(out.includes("language = Language.KOTLIN"), "gradle language enum");
  assert.ok(out.includes("generate = setOf(Role.CONTROLLER, Role.CLIENT)"), "gradle generate setOf");
  assert.ok(out.includes("generateResponseParameter = true"), "gradle response param");
  assert.ok(out.includes("forceSnakeCaseForProperties = false"), "gradle snake case");
}
// --- CLI defaults ---
{
  const out = g.cliSnippet(base);
  assert.ok(out.includes("hurdy-gurdy"), "cli command");
  assert.ok(out.includes("--spec src/main/openapi/api.yaml"), "cli spec");
  assert.ok(out.includes("--root-package com.example.project"), "cli root package");
  assert.ok(out.includes("--output build/generated-sources"), "cli output always present");
  assert.ok(out.includes(`hurdy-gurdy-${g.VERSION}-cli.jar`), "cli jar note uses VERSION");
  assert.ok(!out.includes("--language"), "cli omits default language");
  assert.ok(!out.includes("--framework"), "cli omits default framework");
  assert.ok(!out.includes("--generate"), "cli omits default generate");
  assert.ok(!out.includes("response-parameter"), "cli omits default resp param");
  assert.ok(!out.includes("force-snake-case"), "cli omits default snake case");
}
// --- CLI non-defaults ---
{
  const out = g.cliSnippet({ ...base, language: "kotlin", framework: "quarkus",
    generate: ["controller", "client"], responseParameter: true, forceSnakeCase: false });
  assert.ok(out.includes("--language kotlin"), "cli language");
  assert.ok(out.includes("--framework quarkus"), "cli framework");
  assert.ok(out.includes("--generate controller,client"), "cli generate");
  assert.ok(out.includes("--response-parameter"), "cli response-parameter on");
  assert.ok(out.includes("--no-force-snake-case"), "cli no-force-snake-case");
}
// --- Empty generate falls back to controller (default => omitted) ---
{
  const out = g.mavenSnippet({ ...base, generate: [] });
  assert.ok(!out.includes("<generate>"), "empty generate falls back to controller default (omitted)");
}
// --- Maven XML-escapes special chars in the spec path ---
{
  const out = g.mavenSnippet({ ...base, spec: "openapi/api.yaml?ref=a&env=b" });
  assert.ok(out.includes("<spec>openapi/api.yaml?ref=a&amp;env=b</spec>"), "maven escapes & in spec");
  assert.ok(!out.includes("&env"), "maven leaves no raw & in spec");
}
// --- CLI quotes args containing whitespace, leaves clean ones bare ---
{
  const out = g.cliSnippet({ ...base, spec: "src/open api/api.yaml" });
  assert.ok(out.includes(`--spec "src/open api/api.yaml"`), "cli quotes spec with space");
  assert.ok(out.includes("--output build/generated-sources"), "cli leaves space-free output unquoted");
}
// --- Java DTO style: lombok (default) omitted everywhere ---
{
  const cfg = { ...base, dtoStyle: "lombok" };
  assert.ok(!g.mavenSnippet(cfg).includes("javaDtoStyle"), "maven omits default lombok style");
  assert.ok(!g.gradleSnippet(cfg).includes("JavaDtoStyle"), "gradle omits default lombok style");
  assert.ok(!g.cliSnippet(cfg).includes("--java-dto-style"), "cli omits default lombok style");
}
// --- Java DTO style: records emitted in all three tools ---
{
  const cfg = { ...base, dtoStyle: "records" };
  assert.ok(g.mavenSnippet(cfg).includes("<javaDtoStyle>records</javaDtoStyle>"), "maven records style");
  const gr = g.gradleSnippet(cfg);
  assert.ok(gr.includes("import ru.curs.hurdygurdy.JavaDtoStyle"), "gradle imports JavaDtoStyle");
  assert.ok(gr.includes("javaDtoStyle = JavaDtoStyle.RECORDS"), "gradle records enum");
  assert.ok(g.cliSnippet(cfg).includes("--java-dto-style records"), "cli records style");
}
// --- Java DTO style: pojo (lowercase in maven/cli, enum-cased in gradle) ---
{
  const cfg = { ...base, dtoStyle: "pojo" };
  assert.ok(g.mavenSnippet(cfg).includes("<javaDtoStyle>pojo</javaDtoStyle>"), "maven pojo style");
  assert.ok(g.gradleSnippet(cfg).includes("javaDtoStyle = JavaDtoStyle.POJO"), "gradle pojo enum");
  assert.ok(g.cliSnippet(cfg).includes("--java-dto-style pojo"), "cli pojo style");
}
// --- DTO style is Java-only: never emitted for Kotlin, even when non-default ---
{
  const cfg = { ...base, language: "kotlin", dtoStyle: "records" };
  assert.ok(!g.mavenSnippet(cfg).includes("javaDtoStyle"), "maven omits style for kotlin");
  assert.ok(!g.gradleSnippet(cfg).includes("JavaDtoStyle"), "gradle omits style for kotlin");
  assert.ok(!g.cliSnippet(cfg).includes("--java-dto-style"), "cli omits style for kotlin");
}
console.log("all generator tests passed");
