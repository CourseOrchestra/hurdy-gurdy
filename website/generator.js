// Bump this one line at release time. Drives every output's version string.
const VERSION = "2.11";

const GEN_ORDER = ["controller", "api", "client"];

// Normalize the generate set: fixed order, dedup, fall back to ["controller"].
function normGenerate(generate) {
  const set = new Set(generate || []);
  const ordered = GEN_ORDER.filter((r) => set.has(r));
  return ordered.length ? ordered : ["controller"];
}
const isDefaultGenerate = (g) => g.length === 1 && g[0] === "controller";

// Escape XML text so a spec path with & < > stays valid inside pom.xml.
const xmlEscape = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
// Quote a shell arg only when it contains whitespace (keeps clean paths unquoted).
const shArg = (s) => (/\s/.test(s) ? `"${s}"` : s);

function mavenSnippet(c) {
  const gen = normGenerate(c.generate);
  const lines = [
    `        <rootPackage>${xmlEscape(c.rootPackage)}</rootPackage>`,
    `        <spec>${xmlEscape(c.spec)}</spec>`,
  ];
  if (c.language !== "java") lines.push(`        <language>${c.language}</language>`);
  if (c.framework !== "spring") lines.push(`        <framework>${c.framework}</framework>`);
  if (!isDefaultGenerate(gen)) lines.push(`        <generate>${gen.join(",")}</generate>`);
  if (c.responseParameter) lines.push(`        <generateResponseParameter>true</generateResponseParameter>`);
  if (!c.forceSnakeCase) lines.push(`        <forceSnakeCaseForProperties>false</forceSnakeCaseForProperties>`);
  return [
    `<plugin>`,
    `    <groupId>ru.curs</groupId>`,
    `    <artifactId>hurdy-gurdy</artifactId>`,
    `    <version>${VERSION}</version>`,
    `    <configuration>`,
    ...lines,
    `    </configuration>`,
    `    <executions>`,
    `        <execution><goals><goal>gen-server</goal></goals></execution>`,
    `    </executions>`,
    `</plugin>`,
  ].join("\n");
}

function gradleSnippet(c) {
  const gen = normGenerate(c.generate);
  const useFramework = c.framework !== "spring";
  const useLanguage = c.language !== "java";
  const useGenerate = !isDefaultGenerate(gen);

  const imports = [];
  if (useFramework) imports.push(`import ru.curs.hurdygurdy.Framework`);
  if (useGenerate) imports.push(`import ru.curs.hurdygurdy.Role`);
  if (useLanguage) imports.push(`import ru.curs.hurdygurdy.gradle.Language`);

  const body = [
    `        spec = file("${c.spec}")`,
    `        rootPackage = "${c.rootPackage}"`,
  ];
  if (useLanguage) body.push(`        language = Language.${c.language.toUpperCase()}`);
  if (useFramework) body.push(`        framework = Framework.${c.framework.toUpperCase()}`);
  if (useGenerate) {
    const roles = gen.map((r) => `Role.${r.toUpperCase()}`).join(", ");
    body.push(`        generate = setOf(${roles})`);
  }
  if (c.responseParameter) body.push(`        generateResponseParameter = true`);
  if (!c.forceSnakeCase) body.push(`        forceSnakeCaseForProperties = false`);

  const header = imports.length ? imports.join("\n") + "\n\n" : "";
  return header + [
    `plugins {`,
    `    id("ru.curs.hurdy-gurdy") version "${VERSION}"`,
    `}`,
    ``,
    `hurdyGurdy {`,
    `    api {`,
    ...body,
    `    }`,
    `}`,
  ].join("\n");
}

function cliSnippet(c) {
  const gen = normGenerate(c.generate);
  const args = [
    `--spec ${shArg(c.spec)}`,
    `--root-package ${shArg(c.rootPackage)}`,
    `--output ${shArg(c.outputDir)}`,
  ];
  if (c.language !== "java") args.push(`--language ${c.language}`);
  if (c.framework !== "spring") args.push(`--framework ${c.framework}`);
  if (!isDefaultGenerate(gen)) args.push(`--generate ${gen.join(",")}`);
  if (c.responseParameter) args.push(`--response-parameter`);
  if (!c.forceSnakeCase) args.push(`--no-force-snake-case`);

  const cmd = ["hurdy-gurdy", ...args].join(" \\\n    ");
  return cmd + `\n# or: java -jar hurdy-gurdy-${VERSION}-cli.jar (same flags)`;
}

const api = { VERSION, mavenSnippet, gradleSnippet, cliSnippet };
if (typeof module !== "undefined" && module.exports) {
  module.exports = api;             // Node
} else if (typeof window !== "undefined") {
  Object.assign(window, api);       // Browser
}
