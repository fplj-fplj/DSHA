// Actual pinned frontend React + shipped mobile file button + real authenticated
// DSH file bytes. Only Android's WebMessage/SAF completion is represented here.
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { join } from 'node:path';

export async function exerciseRemoteFileUI({ root, auth, home, output, version }) {
  const require = createRequire(import.meta.url);
  let playwright; try { playwright = require('playwright'); } catch { playwright = require(join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')); }
  const java = await readFile(join(root, 'app/src/main/java/com/deepseekharness/app/remote/gateway/RemoteFileScript.java'), 'utf8');
  const script = java.split('SCRIPT = """')[1].split('""";')[0];
  const controlsHeader = '__modules["components/file-share-controls.js"] = function (require, module, exports) {';
  const mobile = (await readFile(join(root, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js'), 'utf8'))
    .replace('var __cache = {};', 'window.__remoteFileRequire = __localRequire; var __cache = {};')
    .replace(controlsHeader, controlsHeader + '\nexports.auditPlatformDeps = platformDeps;');
  const bytes = Buffer.alloc(160123); for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  const file = join(home, '远程下载 100%＋.txt'); await writeFile(file, bytes);
  const browser = await playwright.chromium.launch({ headless: true });
  const page = await browser.newPage(); const transfers = new Map(); const completed = []; let rejectShare = false, rejectedShares = 0;
  try {
    await page.exposeFunction('__remoteNativeMessage', text => {
      const message = JSON.parse(text);
      assert.equal(message.binding.nonce, 'remote-file-document'); assert.equal(message.binding.generation, 195);
      if (message.action === 'start') { transfers.set(message.transferId, { ...message, chunks: [], received: 0, next: 0 }); return { status: 'ready' }; }
      const transfer = transfers.get(message.transferId); assert.ok(transfer);
      if (message.action === 'chunk') { assert.equal(message.sequence, transfer.next++); const part = Buffer.from(message.data, 'base64'); assert.ok(part.length <= 24576); transfer.chunks.push(part); transfer.received += part.length; return { status: 'ack' }; }
      if (message.action === 'done') { assert.equal(message.bytes, transfer.bytes); assert.equal(transfer.received, transfer.bytes); assert.deepEqual(Buffer.concat(transfer.chunks), bytes);
        transfers.delete(message.transferId);
        if (transfer.destination === 'share' && rejectShare) { rejectedShares++; throw Error('REMOTE_SHARE_UNAVAILABLE'); }
        completed.push({ destination: transfer.destination, bytes: transfer.bytes, name: transfer.name, sha256: createHash('sha256').update(Buffer.concat(transfer.chunks)).digest('hex') }); return { status: transfer.destination === 'share' ? 'chooser-opened' : 'saved' }; }
      throw Error('unexpected transfer action');
    });
    await page.addInitScript({ content: `window.DshaRemoteFiles={postMessage(text){const request=JSON.parse(text);__remoteNativeMessage(text).then(result=>DshaRemoteFiles.onmessage({data:JSON.stringify({id:request.id,ok:true,result})}),error=>DshaRemoteFiles.onmessage({data:JSON.stringify({id:request.id,ok:false,error:String(error)})}));}};\n${script}\n__dshaRemoteFilesBind({nonce:'remote-file-document',generation:195});` });
    // Expose the real renderer without replacing its boot, requests or controls.
    await page.route('**/assets/index-*.js', async route => { const response = await route.fetch(); let source = await response.text(); const match = source.match(/function ([A-Za-z_$][\w$]*)\(\)\{return\{react:/); assert.ok(match); const anchor = source.match(/\bconst\s+[A-Za-z_$][\w$]*=globalThis\.dshDesktopBoot\b/); assert.ok(anchor); source = source.slice(0, anchor.index) + `window.__remoteReactModules=${match[1]}();` + source.slice(anchor.index); await route.fulfill({ response, body: source }); });
    await page.goto(auth); await page.waitForFunction(() => window.__remoteReactModules, undefined, { timeout: 60000 });
    await page.evaluate(() => { window.__savedLoader = window.__ModuleLoader__; window.__ModuleLoader__ = { load: ({ factory }) => factory(name => __remoteReactModules[name]) }; });
    await page.addScriptTag({ content: mobile });
    await page.evaluate(({ file }) => {
      window.__ModuleLoader__ = window.__savedLoader;
      const React = __remoteReactModules.react.default || __remoteReactModules.react;
      const ReactDOM = __remoteReactModules['react-dom'].default || __remoteReactModules['react-dom'];
      const controls = __remoteFileRequire('./components/file-share-controls.js');
      const node = document.createElement('div'); node.id = 'remote-file-controls'; node.style.cssText = 'position:fixed;inset:0;background:white;z-index:100000'; document.body.appendChild(node);
      window.__remoteRender = () => ReactDOM.createRoot(node).render(React.createElement(controls.FilePreviewShareButton, { absolutePath: file, sessionId: 'fixture-session', t: key => key,
        readFileRange: async (_session, path, range, signal) => { const response = await fetch('/api/file?path=' + encodeURIComponent(path), { signal }); if (!response.ok) throw Error('actual DSH file HTTP ' + response.status); const bytes = new Uint8Array(await response.arrayBuffer()); return { ok: true, value: { data: bytes.slice(range.offset, range.offset + range.length), bytes: bytes.length, eof: range.offset + range.length >= bytes.length } }; } }));
      // With no share capability the real control honestly offers Download.
      window.__canShareOriginal = DSHA.canShareFile; window.__shareOriginal = DSHA.shareFile;
      DSHA.canShareFile = undefined; DSHA.shareFile = undefined;
      __remoteRender();
    }, { file });
    await page.locator('#remote-file-controls button').click();
    await page.waitForFunction(() => !document.querySelector('#remote-file-controls button').disabled);
    for (let i = 0; i < 100 && completed.length < 1; i++) await new Promise(r => setTimeout(r, 50));
    assert.equal(completed[0]?.destination, 'save', 'actual download button must reach remote blob anchor bridge');
    await page.evaluate(() => { DSHA.canShareFile = __canShareOriginal; DSHA.shareFile = __shareOriginal; });
    await page.locator('#remote-file-controls button').click();
    for (let i = 0; i < 100 && completed.length < 2; i++) await new Promise(r => setTimeout(r, 50));
    assert.equal(completed[1]?.destination, 'share'); assert.equal(transfers.size, 0);
    rejectShare = true;
    await page.locator('#remote-file-controls button').click();
    for (let i = 0; i < 100 && rejectedShares < 1; i++) await new Promise(r => setTimeout(r, 50));
    await page.waitForFunction(() => !document.querySelector('#remote-file-controls button').disabled);
    assert.equal(rejectedShares, 1); assert.equal(completed.length, 2, 'Remote native share failure cannot become save');
    assert.equal(transfers.size, 0);
    const oversized = await page.evaluate(async () => {
      const core = __remoteFileRequire('./components/file-share-core.js');
      const controls = __remoteFileRequire('./components/file-share-controls.js');
      return core.deliverFile(new File([new Uint8Array(32*1024*1024+1)], 'large.bin'), controls.auditPlatformDeps())
        .then(() => false, error => /32 MiB/.test(error.message));
    });
    assert.equal(oversized, true); assert.equal(completed.length, 2);
    await writeFile(join(output, `remote-file-ui-${version}.json`), JSON.stringify({ status: 'PASS', version, realPinnedFrontendReact: true, realShippedFileButton: true, realAuthenticatedPCFileBytes: true, exactRemoteFileScript: true, nativeReplyMockOnly: true, transfers: completed, failedShareDidNotSave: true, oversizedShareDidNotSave: oversized, androidSAF: 'NOT_TESTED', fiveGBBrowserBlob: 'NOT_CLAIMED' }, null, 2));
  } finally { await browser.close(); }
}
