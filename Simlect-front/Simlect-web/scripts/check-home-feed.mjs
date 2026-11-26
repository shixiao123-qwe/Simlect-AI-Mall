// Run the actual home-feed functions with controlled API responses.
// Usage: node scripts/check-home-feed.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';
import ts from 'typescript';

const names = new Set([
  'categories', 'hotProductsList', 'products', 'loading', 'feedPageNo',
  'feedPageTotal', 'feedLoading', 'feedFinished', 'feedLoadError',
  'MAX_PRODUCTS', 'loadFeed', 'retryFeed', 'load'
]);

for (const file of ['HomeView.vue', 'pc/PcHomeView.vue']) {
  const filename = new URL(`../src/views/${file}`, import.meta.url).pathname;
  const source = readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8');
  const { descriptor, errors } = parse(source, { filename });
  assert.equal(errors.length, 0);
  const compiled = compileScript(descriptor, { id: file });
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: file,
    compilerOptions: { bindingMetadata: compiled.bindings }
  });
  assert.equal(template.errors.length, 0, JSON.stringify(template.errors));

  const script = descriptor.scriptSetup.content;
  const ast = ts.createSourceFile(filename + '.ts', script, ts.ScriptTarget.Latest, true);
  const selected = ast.statements.filter((s) =>
    ts.isVariableStatement(s) && s.declarationList.declarations.some((d) =>
      ts.isIdentifier(d.name) && names.has(d.name.text))
  ).map((s) => s.getText(ast)).join('\n');
  const js = ts.transpileModule(selected, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }
  }).outputText;

  function fixture(replies) {
    const calls = [];
    const context = {
      ref: (value) => ({ value }),
      console: { error() {} },
      normalizeCategoryTree: (items) => items,
      filterOnSaleProducts: (items) => items ?? [],
      productApi: {
        loadCategory: async () => [],
        loadCommendProduct: async () => [],
        loadProduct: async ({ pageNo, includeRecommended }) => {
          assert.equal(includeRecommended, true, 'Home explicitly includes recommended products');
          calls.push(pageNo);
          // Stop a regressed infinite retry loop without allocating indefinitely.
          if (calls.length > 10) {
            process.stderr.write(`${file}: runaway automatic retries\n`);
            process.exit(1);
          }
          const reply = replies.shift();
          if (reply instanceof Error) throw reply;
          assert.ok(reply, 'Unexpected request');
          return reply;
        }
      }
    };
    vm.runInNewContext(js + '\nglobalThis.feed = { load, loadFeed, retryFeed, products, feedPageNo, feedLoadError, feedLoading, feedFinished, loading };', context);
    return { ...context.feed, calls };
  }

  const empty = { list: [], pageNo: 1, pageTotal: 1 };
  const denied = fixture([new Error('HTTP 403'), empty]);
  await denied.load({ prefetch: true });
  assert.deepEqual(denied.calls, [1], 'Initial failure must stop prefetch');
  assert.equal(denied.loading.value, false);
  assert.equal(denied.feedLoadError.value, true);
  for (let i = 0; i < 5; i++) await denied.loadFeed();
  assert.deepEqual(denied.calls, [1], 'Scroll must not retry a failed request');
  await denied.retryFeed();
  assert.deepEqual(denied.calls, [1, 1], 'User retry should repeat the failed page');
  assert.equal(denied.feedLoadError.value, false);
  assert.equal(denied.feedFinished.value, true);

  const laterFailure = fixture([
    { list: [{ productId: 'one' }], pageNo: 1, pageTotal: 2 },
    new Error('HTTP 500'),
    { list: [{ productId: 'two' }], pageNo: 2, pageTotal: 2 }
  ]);
  await laterFailure.load({ prefetch: true });
  assert.deepEqual(laterFailure.calls, [1, 2]);
  assert.equal(laterFailure.products.value.length, 1);
  assert.equal(laterFailure.feedPageNo.value, 1);
  await laterFailure.retryFeed();
  assert.deepEqual(laterFailure.calls, [1, 2, 2]);
  assert.equal(laterFailure.products.value.length, 2);
  assert.equal(laterFailure.feedFinished.value, true);
  console.log(`${file}: compile, failure stop, manual retry and pagination recovery passed`);
}
