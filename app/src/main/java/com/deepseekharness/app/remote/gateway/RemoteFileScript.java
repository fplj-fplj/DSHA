package com.deepseekharness.app.remote.gateway;

/** Only remote file bytes cross this bridge. No local runtime API or native credential is exposed. */
public final class RemoteFileScript {
  private RemoteFileScript() {}

  public static final String SCRIPT =
      """
      (() => {
        if (window !== window.top || !window.DshaRemoteFiles || window.__dshaRemoteFilesInstalled) return;
        window.__dshaRemoteFilesInstalled = true;
        let binding = null, active = true, busy = false;
        const pending = new Map();
        window.__dshaRemoteFilesBind = value => { binding = value; active = true; };
        function message(action, fields) {
          if (!active || !binding) return Promise.reject(new Error('Remote file bridge is not ready'));
          const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
          return new Promise((resolve, reject) => {
            const timer = action === 'start' ? null : setTimeout(() => {
              pending.delete(id); reject(new Error('Remote file transfer timed out; check the selected destination'));
            }, 30000);
            pending.set(id, {resolve, reject, timer});
            try { DshaRemoteFiles.postMessage(JSON.stringify({id, action, binding, ...fields})); }
            catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
          });
        }
        DshaRemoteFiles.onmessage = event => {
          let response; try { response = JSON.parse(event.data); } catch (_) { return; }
          const item = pending.get(response.id); if (!item) return;
          pending.delete(response.id); clearTimeout(item.timer);
          if (response.ok) item.resolve(response.result);
          else { const error = new Error(response.error || 'Remote file transfer failed');
            if (response.error === 'AbortError') error.name = 'AbortError'; item.reject(error); }
        };
        async function transfer(file, destination) {
          if (!(file instanceof Blob)) throw new TypeError('Expected a File or Blob');
          if (busy) throw new Error('A remote file transfer is already pending');
          const limit = destination === 'share' ? 32 * 1024 * 1024 : 5000000000;
          if (!Number.isSafeInteger(file.size) || file.size > limit) throw new Error('File is too large');
          if (destination === 'gallery' && !file.type.startsWith('image/')) throw new TypeError('Expected an image');
          busy = true;
          const transferId = Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
          try {
            await message('start', {transferId, destination, bytes: file.size,
              name: file.name || 'download.bin', mime: file.type || 'application/octet-stream'});
            let sequence = 0;
            for (let offset = 0; offset < file.size; offset += 24576) {
              const bytes = new Uint8Array(await file.slice(offset, Math.min(offset + 24576, file.size)).arrayBuffer());
              let binary = ''; for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
              // One acknowledged chunk in flight; never base64 or arrayBuffer the entire file.
              await message('chunk', {transferId, sequence: sequence++, data: btoa(binary)});
            }
            return await message('done', {transferId, bytes: file.size});
          } finally { busy = false; }
        }
        const api = window.DSHA || (window.DSHA = {});
        api.origin = 'REMOTE';
        api.canShareFile = file => file instanceof Blob && file.size <= 32 * 1024 * 1024;
        api.saveFile = file => transfer(file, 'save');
        api.shareFile = file => transfer(file, 'share');
        api.saveImage = file => transfer(file, 'gallery');
        document.addEventListener('click', event => {
          const link = event.target?.closest?.('a[download]');
          if (!link || event.defaultPrevented || !link.href.startsWith('blob:' + location.origin + '/')) return;
          event.preventDefault();
          const name = link.download || 'download.bin';
          fetch(link.href).then(response => { if (!response.ok) throw new Error('Unable to read file'); return response.blob(); })
            .then(blob => api.saveFile(new File([blob], name, {type: blob.type})))
            .catch(error => { if (error.name !== 'AbortError') alert('Remote file not saved: ' + error.message); });
        }, true);
        window.addEventListener('pagehide', () => {
          active = false; binding = null;
          for (const item of pending.values()) { clearTimeout(item.timer); item.reject(new Error('Remote page closed; incomplete file transfer cannot resume')); }
          pending.clear();
        });
      })();
      """;
}
