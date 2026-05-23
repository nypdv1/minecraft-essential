const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

const groups = new Map();
const meta = new WeakMap();

wss.on('connection', (ws) => {
  meta.set(ws, {});

  ws.on('message', (data) => {
    if (ws.readyState !== WebSocket.OPEN) return;

    console.log(`[MESSAGE] type=${typeof data}, isBuffer=${Buffer.isBuffer(data)}, length=${Buffer.isBuffer(data) ? data.length : 'N/A'}`);

    const str = typeof data === 'string' ? data : (Buffer.isBuffer(data) ? data.toString() : null);
    if (str) {
      try {
        const msg = JSON.parse(str);
        console.log(`[TEXT] parsed JSON:`, msg);
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
          
          // Notify existing peers about the new player joining
          const joinMsg = JSON.stringify({ 
            type: 'peerJoined', 
            uuid: msg.uuid, 
            username: msg.username 
          });
          let notifiedCount = 0;
          for (const peer of group) {
            if (peer !== ws && peer.readyState === WebSocket.OPEN) {
              peer.send(joinMsg);
              console.log(`[PEER_JOIN_NOTIFY] sent peerJoined to ${meta.get(peer)?.username}`);
              notifiedCount++;
            }
          }
          console.log(`[PEER_JOIN] notified ${notifiedCount} existing peers`);
          
          // Also tell the new player they joined (so they broadcast their outfit)
          ws.send(JSON.stringify({ 
            type: 'youJoined', 
            uuid: msg.uuid, 
            username: msg.username 
          }));
          return;
        }
      } catch (_) {}
    }

    const info = meta.get(ws);
    console.log(`[BINARY] forwarding to peers, info=`, info);
    if (!info || !info.server) return;

    const group = groups.get(info.server);
    if (!group) return;

    let forwarded = 0;
    for (const peer of group) {
      if (peer !== ws && peer.readyState === WebSocket.OPEN) {
        peer.send(data);
        forwarded++;
      }
    }
    console.log(`[BINARY] forwarded to ${forwarded} peers`);
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
