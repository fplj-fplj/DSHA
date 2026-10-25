// This route uses public Agent cancellation and waits for quiescence. The
// ordinary DSH session/cancel preserves queued input and cannot promise stop.
export function installSessionControl(ctx) {
  ctx.inject(['webServer', 'connection', 'sessionController'], web => {
    web.effect(() => web.webServer.register({ kind: 'exact', path: '/api/dsha-connect/session.cancel', handler: async (req, res) => {
      const respond = (status, value) => { const b = JSON.stringify(value); res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b), 'Cache-Control': 'no-store' }); res.end(b); };
      const rejection = web.connection.requestRejection(req);
      if (rejection !== undefined) return respond(rejection, { error: 'authentication required' });
      if (req.method !== 'POST') return respond(405, { error: 'POST required' });
      if (req.headers.origin) { try { if (new URL(req.headers.origin).host !== req.headers.host) return respond(403, { error: 'cross origin' }); } catch { return respond(403, { error: 'invalid origin' }); } }
      let bytes = 0; const chunks = [];
      const bodyDeadline = setTimeout(() => req.destroy(Error('request body deadline')), 10000);
      try { for await (const chunk of req) { bytes += chunk.length; if (bytes > 16384) return respond(413, { error: 'request too large' }); chunks.push(chunk); } }
      catch { if (!res.destroyed) return respond(400, { error: 'request body incomplete' }); return; }
      finally { clearTimeout(bodyDeadline); }
      let body; try { body = JSON.parse(Buffer.concat(chunks)); } catch { return respond(400, { error: 'invalid body' }); }
      if (typeof body.sessionId !== 'string' || !body.sessionId) return respond(400, { error: 'sessionId required' });
      try {
        const found = await web.sessionController.resolveAgent(body.sessionId);
        if (found.error) return respond(409, { error: String(found.error.message) });
        found.agent.cancel({ kind: 'user' }, { keepInbox: false });
        let timer; const disconnected = new AbortController();
        const close = () => { if (!res.writableEnded) disconnected.abort(); };
        res.on('close', close);
        try {
          await Promise.race([found.agent.whenIdle(), new Promise((_, reject) => {
            const stop = () => reject(Error('client disconnected; stopped state unknown'));
            disconnected.signal.addEventListener('abort', stop, { once: true });
            timer = setTimeout(() => reject(Error('agent still settling; stopped state unknown')), 25000);
          })]);
        } finally { clearTimeout(timer); res.off('close', close); }
        return respond(200, { accepted: true, sessionId: body.sessionId, terminalKnown: true, running: false, queued: 0 });
      } catch (error) { return respond(409, { error: error.message, terminalKnown: false }); }
    } }));
  });
}
