import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {testRuntime} from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const recipe = JSON.parse(await readFile(path.join(root, 'app/src/main/assets/lexical-claim-patch.json'), 'utf8'));
const expectedVersion = JSON.parse(await readFile(path.join(root, 'tools/dsh-runtime/package.json'), 'utf8')).dependencies['@deepseek-ai/dsh'];
const bytes = await readFile(path.join(runtime, 'node_modules', '@deepseek-ai', 'dsh-client-ui-conversation', 'lib', 'client.js'));
const source = bytes.toString('utf8');
assert.equal(recipe.dshVersion, expectedVersion);
assert.equal(recipe.version, 1);
assert.equal(recipe.retired, true);
assert.equal(recipe.module, '@deepseek-ai/dsh-client-ui-conversation/lib/client.js');
assert.deepEqual(recipe.patches, []);
assert.equal(createHash('sha256').update(bytes).digest('hex'), recipe.sourceSha256);
const archive = path.resolve(process.env.DSHA_RUNTIME_ARCHIVE || path.join(root, 'app/src/main/assets/dsh-runtime.bin'));
const archiveEntry = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js';
const archiveSource = execFileSync('tar', ['-xOf', archive, archiveEntry], {
  encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true
});
// 上游已使用原子 CommandChipNode；实际归档必须保留原实现，不恢复退休的文本样式补丁。
function commandChip(text) {
  assert.equal(text.split('class CommandChipNode').length, 2);
  const begin = text.indexOf('class CommandChipNode');
  const end = text.indexOf('//#endregion', begin);
  assert.ok(end > begin);
  const chip = text.slice(begin, end);
  assert.match(chip, /data-composer-command/);
  assert.match(chip, /contenteditable", "false"/);
  return chip;
}
assert.equal(commandChip(archiveSource), commandChip(source), 'archive must retain the exact official CommandChip implementation');
for (const text of [source, archiveSource]) {
  assert.doesNotMatch(text, /DSHA_CLAIM_DECOR_OVERFLOW_STYLE_V1/);
  assert.doesNotMatch(text, /TOKEN_STYLE/);
  assert.doesNotMatch(text, /node\.splitText\(token\.length\)/);
}
console.log('PASS: locked official CommandChip bytes are preserved in the archive and retired claim styling is absent');
