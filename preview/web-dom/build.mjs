import {build} from 'esbuild';
import {readFile, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';

const output = process.env.WALLHUB_OUTPUT_DIR;
const sourceHash = process.env.WALLHUB_SOURCE_HASH;
if (!output || !sourceHash) throw new Error('WALLHUB_OUTPUT_DIR and WALLHUB_SOURCE_HASH are required');
const result = await build({
  entryPoints: ['src/main.js'],
  bundle: true,
  minify: true,
  sourcemap: true,
  outdir: output,
  entryNames: 'assets/[name]-[hash]',
  assetNames: 'assets/[name]-[hash]',
  loader: {'.ttf': 'file'},
  metafile: true,
});
const outputs = Object.keys(result.metafile.outputs);
const relative = (path) => `/releases/${sourceHash}/${resolve(path).slice(resolve(output).length + 1).replaceAll('\\', '/')}`;
const script = outputs.find((path) => path.endsWith('.js'));
const stylesheet = outputs.find((path) => path.endsWith('.css'));
if (!script || !stylesheet) throw new Error(`Missing JS/CSS outputs: ${JSON.stringify(outputs)}`);
const template = await readFile('index.html', 'utf8');
const contract = JSON.parse(await readFile('../../shared/ui-contract/wallhub-current.json', 'utf8'));
const html = template
  .replace('{{SOURCE_HASH}}', sourceHash)
  .replace('{{MEDIUM_BREAKPOINT}}', String(contract.breakpoints.medium))
  .replace('{{EXPANDED_BREAKPOINT}}', String(contract.breakpoints.expanded))
  .replace('{{STYLESHEET}}', relative(stylesheet))
  .replace('{{SCRIPT}}', relative(script));
await writeFile(`${output}/index.html`, html);
await writeFile(`${output}/.wallhub-source-sha`, `${sourceHash}\n`);
console.log(`DOM_BUNDLE=OK js=${relative(script)} css=${relative(stylesheet)}`);
