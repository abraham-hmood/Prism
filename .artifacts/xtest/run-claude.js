const fs = require('fs'), path = require('path'), vm = require('vm'), Module = require('module');
const extPath = path.resolve('.artifacts/claude/extension.js');
const messages = [];
const self = {
  postMessage(m) { messages.push(m); if (m.type !== 'host-ready') console.log('MSG', JSON.stringify(m).slice(0, 600)); },
  onmessage: null,
};
const ctx = { self, console, setTimeout, clearTimeout, setInterval, clearInterval, Promise, TextDecoder, TextEncoder, JSON, Object, Array, String, Error, RegExp, encodeURI, Buffer, process, URL, URLSearchParams, AbortController };
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(fs.readFileSync('app/src/main/assets/editor/extension-host.js', 'utf8'), ctx);
const req = Module.createRequire(extPath);
const orig = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'vscode') return self.prismVscodeFor('Anthropic.claude-code');
  return orig.apply(this, arguments);
};
self.prismLoadModule = function (id, entry) { return req(entry); };
self.onmessage({ data: {
  type: 'activate', id: 'Anthropic.claude-code', path: path.dirname(extPath), source: extPath,
  storage: '/tmp/claude/storage', globalStorage: '/tmp/claude/global', logs: '/tmp/claude/logs',
  state: { global: {}, workspace: {} }, manifest: JSON.parse(fs.readFileSync('.artifacts/claude/package.json', 'utf8'))
}});
setTimeout(() => { self.onmessage({ data: { type: 'open-view', extensionId: 'Anthropic.claude-code' } }); }, 2500);
setTimeout(() => process.exit(0), 7000);
