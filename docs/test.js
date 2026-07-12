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
}
// --- Gradle defaults ---
{
  const out = g.gradleSnippet(base);
  assert.ok(out.includes(`id("ru.curs.hurdy-gurdy") version "${g.VERSION}"`), "gradle plugin id+version");
  assert.ok(out.includes("hurdyGurdy {"), "gradle extension block");
  assert.ok(out.includes("api {"), "gradle named block");
  assert.ok(out.includes(`spec = file("src/main/openapi/api.yaml")`), "gradle spec");
  assert.ok(out.includes(`rootPackage = "com.example.project"`), "gradle rootPackage");
  assert.ok(!out.includes("Framework"), "gradle omits default framework + import");
  assert.ok(!out.includes("Role"), "gradle omits default generate + import");
  assert.ok(!out.includes("Language"), "gradle omits default language + import");
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
console.log("all generator tests passed");
