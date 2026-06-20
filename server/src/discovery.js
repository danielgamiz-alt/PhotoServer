'use strict';

const dgram = require('dgram');

const DISCOVER_REQUEST = 'PHOTOSERVER_DISCOVER_V1';

/**
 * Answers UDP broadcasts from phones looking for a server on the LAN.
 * The phone broadcasts DISCOVER_REQUEST to the discovery port; we reply
 * with a JSON blob telling it where the HTTP API lives.
 *
 * `log(level, message)` is an optional sink for status/errors; defaults to
 * console so the headless CLI keeps printing as before.
 */
function start(config, log) {
  const emit = typeof log === 'function'
    ? log
    : (level, message) => (level === 'error' ? console.error : console.log)(message);
  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

  socket.on('message', (msg, rinfo) => {
    if (msg.toString('utf8').trim() !== DISCOVER_REQUEST) return;
    const reply = JSON.stringify({
      type: 'PHOTOSERVER_HERE_V1',
      serverId: config.serverId,
      name: config.serverName,
      port: config.port,
      requiresApiKey: config.apiKey !== '',
    });
    socket.send(reply, rinfo.port, rinfo.address, (err) => {
      if (err) emit('error', `discovery reply failed: ${err.message}`);
    });
  });

  socket.on('error', (err) => {
    emit('error', `discovery socket error: ${err.message} (auto-discovery disabled)`);
    socket.close();
  });

  socket.bind(config.discoveryPort, () => {
    emit('info', `Discovery listening on UDP ${config.discoveryPort}`);
  });

  return socket;
}

module.exports = { start, DISCOVER_REQUEST };
