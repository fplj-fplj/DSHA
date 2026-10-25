import { readFile, lstat } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

export function defaultIPCPath(env = process.env) {
  return env.DSHA_CONNECT_IPC || (process.platform === 'win32'
    ? join(env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local'), 'DSHA', 'Connect', 'ipc.json')
    : join(env.XDG_CONFIG_HOME || join(homedir(), '.config'), 'dsha-connect', 'ipc.json'));
}
export async function readIPC(path = defaultIPCPath()) {
  const stat = await lstat(path);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 4096) throw Error('无效的 companion IPC 文件');
  if (process.platform !== 'win32' && (stat.mode & 0o077)) throw Error('companion IPC 文件必须仅当前用户可读写');
  const value = JSON.parse(await readFile(path, 'utf8'));
  const url = new URL(value.url);
  if (value.v !== 1 || url.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(url.hostname) || !url.port || url.username || url.password || url.pathname !== '/' || url.search || url.hash || !/^[a-f0-9]{64}$/.test(value.token)) throw Error('无效的 companion IPC 身份');
  return value;
}
export function createClient({ ipcPath, fetcher = fetch } = {}) {
  return async function request(method, args = {}, signal, { targetId = 'physical-screen', requestId = randomUUID() } = {}) {
    const ipc = await readIPC(ipcPath);
    const status = method === 'connection.status';
    let response;
    try {
      response = await fetcher(`${ipc.url}${status ? '/v1/status' : '/v1/device'}`, {
        method: status ? 'GET' : 'POST', redirect: 'error', signal,
        headers: { Authorization: `Bearer ${ipc.token}`, 'Content-Type': 'application/json' },
        ...(status ? {} : { body: JSON.stringify({ v: 1, op: 'device', requestId, targetId, method, args }) }),
      });
      if (!response.ok) throw Error(`companion 返回 HTTP ${response.status}`);
      const declared = Number(response.headers.get('content-length'));
      if (Number.isFinite(declared) && declared > 16 * 1024 * 1024) throw Error('响应过大');
      const reader = response.body.getReader(); const chunks = []; let size = 0;
      for (;;) { const { done, value } = await reader.read(); if (done) break; size += value.length; if (size > 16 * 1024 * 1024) { await reader.cancel(); throw Error('响应过大'); } chunks.push(value); }
      const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (status) return value;
      if (value.v !== 1 || value.requestId !== requestId) throw Error('响应身份不匹配');
      if (!value.ok) {
        const error = Error([value.error?.code, value.error?.guidance || value.error?.message]
          .filter(part => typeof part === 'string' && part).join(' · ') || '远程请求被拒绝');
        error.nativeRefusal = true;
        throw error;
      }
      return value.result;
    } catch (error) {
      if (error.nativeRefusal) throw error;
      // Never infer NOT_SENT from a broken network response. Native ScreenReply
      // is authoritative when returned; unknown dispatch results are not retried.
      throw Error(`远程操作结果未知；不要自动重放。${error.message}`, { cause: error });
    }
  };
}
