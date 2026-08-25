// Bundles each Lambda handler under src/lambda/<name>/index.ts to
// dist/<name>/index.mjs. One bundle per handler, @aws-sdk/* left external since the
// nodejs22.x runtime provides it. No transpilation-at-deploy: this is the only build
// step, and `make deploy` always runs it before `terraform apply`.
import { build } from "esbuild";
import { readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const lambdaRoot = "src/lambda";
const handlers = readdirSync(lambdaRoot, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .filter((name) => existsSync(join(lambdaRoot, name, "index.ts")));

if (handlers.length === 0) {
  console.error(`No handlers found under ${lambdaRoot}/*/index.ts`);
  process.exit(1);
}

for (const name of handlers) {
  const entry = join(lambdaRoot, name, "index.ts");
  await build({
    entryPoints: [entry],
    outfile: `dist/${name}/index.mjs`,
    bundle: true,
    platform: "node",
    target: "node22",
    format: "esm",
    minify: false,
    sourcemap: true,
    external: ["@aws-sdk/*"],
    // Lambda's Node ESM loader needs require() to exist for any CJS-only transitive
    // dependency that esbuild can't fully convert.
    banner: {
      js: "import { createRequire as __createRequire } from 'module'; const require = __createRequire(import.meta.url);",
    },
    logLevel: "info",
  });
  console.log(`built dist/${name}/index.mjs`);
}
