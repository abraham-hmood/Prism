// Stands in for Kotlin: opens the loopback listener, checks the token, drives one activation.
const net = require('net');
const cp = require('child_process');
const path = require('path');
const fs = require('fs');

const TOKEN = 'test-token-0123456789';
const here = __dirname;
// The real host scripts, not copies: a divergence between what is tested and what ships would
// make this harness worse than no harness at all.
const assets = path.resolve(here, '..', '..', 'app', 'src', 'main', 'assets', 'editor');
const extDir = path.join(here, 'ext');
const storage = path.join(here, 'storage');
const globalStorage = path.join(here, 'global-storage');
const logs = path.join(here, 'logs');
[storage, globalStorage, logs].forEach(d => fs.mkdirSync(d, { recursive: true }));
try { fs.unlinkSync(path.join(here, 'report.json')); } catch (e) {}

const received = [];
let child;

const server = net.createServer(socket => {
  let buffer = '';
  let authed = false;
  socket.setEncoding('utf8');
  socket.on('data', chunk => {
    buffer += chunk;
    let i;
    while ((i = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, i);
      buffer = buffer.slice(i + 1);
      if (!authed) {
        if (line !== TOKEN) { console.log('FAIL: bad token, got: ' + JSON.stringify(line.slice(0,120))); process.exit(1); }
        authed = true;
        console.log('ok: token accepted');
        socket.write(JSON.stringify({
          type: 'activate',
          id: 'test.probe',
          path: extDir,
          entry: path.join(extDir, 'main.js'),
          storage, globalStorage, logs,
          manifest: JSON.parse(fs.readFileSync(path.join(extDir, 'package.json'), 'utf8')),
          state: { global: { seeded: 'from-disk' }, workspace: {} }
        }) + '\n');
        continue;
      }
      if (!line) continue;
      let msg; try { msg = JSON.parse(line); } catch (e) { continue; }
      received.push(msg);
      if (msg.type === 'activated' || msg.type === 'activation-failed') {
        setTimeout(() => finish(msg), 400);
      }
    }
  });
});

function finish(last) {
  console.log('host messages:', received.map(m => m.type).join(', '));
  if (last.type === 'activation-failed') {
    console.log('FAIL: activation failed:', last.message);
    cleanup(1);
  }
  let report;
  try { report = JSON.parse(fs.readFileSync(path.join(here, 'report.json'), 'utf8')); }
  catch (e) { console.log('FAIL: the extension never wrote its report'); return cleanup(1); }

  const checks = [
    ['subscriptions is an array', report.hasSubscriptions === true],
    ['extensionPath is the extension directory', report.extensionPath === extDir],
    ['globalStorageUri.fsPath is set', report.globalStorageFsPath === globalStorage],
    ['storagePath is set', report.storagePath === storage],
    ['logPath is set', report.logPath === logs],
    ['secrets exists', report.hasSecrets === true],
    ['environmentVariableCollection exists', report.hasEnvCollection === true],
    ['manifest reached the extension', report.manifestName === 'probe'],
    ['globalState was seeded from disk', report.globalStateSeeded === 'from-disk'],
    ['asAbsolutePath resolves', report.asAbsolute === extDir + '/main.js'],
    ['__dirname is real', report.dirname === extDir],
    ['child_process works', report.childProcess === 'spawned'],
    ['fs can read its own files', report.canReadOwnFile === true],
    ['commands API present', report.hasCommands === true],
    ['window API present', report.hasWindow === true],
    ['workspace API present', report.hasWorkspace === true],
    ['registerCommand reached the host', received.some(m => m.type === 'command-registered')],
    ['a memento write was reported', received.some(m => m.type === 'save-state' && m.state && m.state.seeded === 'yes')]
  ];
  let bad = 0;
  checks.forEach(([name, ok]) => { console.log((ok ? 'ok  ' : 'FAIL') + ': ' + name); if (!ok) bad++; });
  console.log(bad === 0 ? '\nALL PASS' : '\n' + bad + ' FAILED');
  cleanup(bad === 0 ? 0 : 1);
}

function cleanup(code) {
  try { child && child.kill(); } catch (e) {}
  try { server.close(); } catch (e) {}
  process.exit(code);
}

server.listen(0, '127.0.0.1', () => {
  const port = server.address().port;
  child = cp.spawn(process.execPath, [path.join(assets, 'node-extension-host.js')], {
    env: Object.assign({}, process.env, {
      PRISM_EDITOR_PORT: String(port),
      PRISM_EDITOR_TOKEN: TOKEN,
      PRISM_EDITOR_ASSETS: assets
    }),
    stdio: ['ignore', 'pipe', 'pipe']
  });
  child.stdout.on('data', d => process.stdout.write('[node] ' + d));
  child.stderr.on('data', d => process.stdout.write('[node:err] ' + d));
  setTimeout(() => { console.log('FAIL: timed out'); cleanup(1); }, 20000);
});
