const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

const groups = new Map();
const meta = new WeakMap();

wss.on('connection', (ws) => {
  meta.set(ws, {});

  ws.on('message', (data) => {
    if (ws.readyState !== WebSocket.OPEN) return;

    const str = typeof data === 'string' ? data : (Buffer.isBuffer(data) ? data.toString() : null);
    if (str) {
      try {
        const msg = JSON.parse(str);
        if (msg.type === 'identify') {
          const info = { uuid: msg.uuid, username: msg.username, server: msg.server };
          meta.set(ws, info);

          let group = groups.get(info.server);
          if (!group) {
            group = new Set();
            groups.set(info.server, group);
          }
          group.add(ws);
          console.log(`[+] ${info.username} (${info.uuid}) on ${info.server} [${group.size}]`);
          ws.send(JSON.stringify({ type: 'identified' }));
          return;
        }
      } catch (_) {}
    }

    const info = meta.get(ws);
    if (!info || !info.server) return;

    const group = groups.get(info.server);
    if (!group) return;

    for (const peer of group) {
      if (peer !== ws && peer.readyState === WebSocket.OPEN) {
        peer.send(data);
      }
    }
  });

  ws.on('close', () => {
    const info = meta.get(ws);
    if (info && info.server) {
      const group = groups.get(info.server);
      if (group) {
        group.delete(ws);
        console.log(`[-] ${info.username} left ${info.server} [${group.size} remaining]`);
        if (group.size === 0) groups.delete(info.server);
      }
    }
  });
});

console.log(`Prometheus relay running on :${PORT}`);
