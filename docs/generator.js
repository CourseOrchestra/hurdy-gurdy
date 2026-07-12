// The version shown in every snippet is fetched live from Maven Central at page
// load (see fetchLatestVersion). Until that resolves — or if it fails (offline,
// Shields down) — this placeholder renders instead, so a stale hardcoded number
// never silently ships inside copied config.
const VERSION_FALLBACK = "???";
let version = VERSION_FALLBACK;

// Overwrite the version used by every snippet. Called after a successful fetch.
function setVersion(v) { version = v; }

// Ask Maven Central for the latest published version, via Shields.io's JSON
// endpoint. We can't hit Maven Central directly: neither its search API nor
// maven-metadata.xml sends CORS headers, so the browser blocks the read. Shields
// proxies the canonical maven-metadata.xml with `Access-Control-Allow-Origin: *`.
// Resolves to the version on success; leaves the placeholder in place (and
// rejects) on any failure, so the caller can just render what it has.
async function fetchLatestVersion() {
  const url = "https://img.shields.io/maven-central/v/ru.curs/hurdy-gurdy.json";
  const res = await fetch(url);
  if (!res.ok) throw new Error(`shields.io responded ${res.status}`);
  const { value } = await res.json();          // e.g. "v2.10"
  const v = String(value).replace(/^v/, "");   // strip Shields' leading "v"
  setVersion(v);
  return v;
}

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

// The Java DTO style is Java-only (Kotlin always emits data classes) and lombok
// is the default, so it's emitted only for a non-default Java style.
const useDtoStyle = (c) => c.language === "java" && c.dtoStyle && c.dtoStyle !== "lombok";

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
  if (useDtoStyle(c)) lines.push(`        <javaDtoStyle>${c.dtoStyle}</javaDtoStyle>`);
  // Kotlin output only compiles if the project also runs the kotlin-maven-plugin;
  // the goal fails fast otherwise. The form emits only the hurdy-gurdy plugin, so
  // flag the extra requirement rather than silently producing an un-buildable pom.
  const kotlinNote = c.language !== "java"
    ? "<!-- Kotlin output requires the kotlin-maven-plugin to be configured in this\n     project (in <build><plugins>), otherwise the build fails. -->\n"
    : "";
  return kotlinNote + [
    `<plugin>`,
    `    <groupId>ru.curs</groupId>`,
    `    <artifactId>hurdy-gurdy</artifactId>`,
    `    <version>${version}</version>`,
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
  const useStyle = useDtoStyle(c);

  const imports = [];
  if (useFramework) imports.push(`import ru.curs.hurdygurdy.Framework`);
  if (useGenerate) imports.push(`import ru.curs.hurdygurdy.Role`);
  if (useLanguage) imports.push(`import ru.curs.hurdygurdy.gradle.Language`);
  if (useStyle) imports.push(`import ru.curs.hurdygurdy.JavaDtoStyle`);

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
  if (useStyle) body.push(`        javaDtoStyle = JavaDtoStyle.${c.dtoStyle.toUpperCase()}`);

  const header = imports.length ? imports.join("\n") + "\n\n" : "";
  return header + [
    `plugins {`,
    `    id("ru.curs.hurdy-gurdy") version "${version}"`,
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
  if (useDtoStyle(c)) args.push(`--java-dto-style ${c.dtoStyle}`);

  const cmd = ["hurdy-gurdy", ...args].join(" \\\n    ");
  return cmd + `\n# or: java -jar hurdy-gurdy-${version}-cli.jar (same flags)`;
}

const api = {
  get VERSION() { return version; },   // live value the snippets currently emit
  VERSION_FALLBACK,
  setVersion,
  fetchLatestVersion,
  mavenSnippet, gradleSnippet, cliSnippet,
};
if (typeof module !== "undefined" && module.exports) {
  module.exports = api;             // Node
} else if (typeof window !== "undefined") {
  Object.assign(window, api);       // Browser
}
