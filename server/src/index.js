'use strict';

// Headless CLI entry point. Thin wrapper around the PhotoServer module so the
// same server logic powers both this and the desktop tray app.

const { load } = require('./config');
const { PhotoServer } = require('./server');

async function main() {
  const config = load(process.argv.slice(2));
  const server = new PhotoServer(config);

  server.on('log', ({ level, message }) => {
    (level === 'error' ? console.error : console.log)(message);
  });

  server.on('started', (info) => {
    console.log(`Storing photos in: ${info.storagePath} (${info.fileCount} files indexed)`);
    console.log(`API key required: ${info.requiresApiKey ? 'yes' : 'no'}`);
    console.log('Listening on:');
    for (const addr of info.addresses) {
      console.log(`  http://${addr}:${info.port}`);
    }
  });

  await server.start();
}

main().catch((err) => {
  console.error('fatal:', err);
  process.exit(1);
});
