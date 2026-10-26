const fs = require('fs');
const vm = require('vm');
const path = require('path');

function boot() {
  const src = fs.readFileSync('app/src/main/assets/editor/extension-host.js', 'utf8');
  const messages = [];
  const self = {
    postMessage(m) { messages.push(JSON.parse(JSON.stringify(m))); },
    onmessage: null,
  };
  const ctx = { self, console, setTimeout, clearTimeout, Promise, TextDecoder, JSON, Object, Array, String, Error, RegExp, encodeURI };
  ctx.globalThis = ctx;
  vm.createContext(ctx);
  vm.runInContext(src, ctx, { filename: 'extension-host.js' });
  return { self, messages, ctx };
}

const extSource = `
const vscode = require('vscode');
function activate(context) {
  const provider = {
    resolveWebviewView(webviewView) {
      webviewView.webview.options = { enableScripts: true };
      webviewView.webview.html = '<html><body>HELLO VIEW ' + Date.now() + '</body></html>';
    }
  };
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('demo.sidebar', provider, { webviewOptions: { retainContextWhenHidden: true } }));
}
exports.activate = activate;
`;

const h = boot();
console.log('initial', JSON.stringify(h.messages));
h.self.onmessage({ data: { type: 'open-view', extensionId: 'demo.ext' } });
console.log('open before activate', JSON.stringify(h.messages.slice(-3)));
h.self.onmessage({ data: { type: 'activate', id: 'demo.ext', path: '/tmp/demo.ext', source: extSource, storage:'/tmp/s', globalStorage:'/tmp/g', logs:'/tmp/l', manifest:{} } });
setTimeout(() => {
  console.log('after activate', JSON.stringify(h.messages, null, 1));
  h.messages.length = 0;
  h.self.onmessage({ data: { type: 'open-view', extensionId: 'demo.ext' } });
  setTimeout(() => console.log('open after', JSON.stringify(h.messages, null, 1)), 50);
}, 50);
