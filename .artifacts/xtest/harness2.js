const fs = require('fs'), vm = require('vm');
function boot() {
  const src = fs.readFileSync('app/src/main/assets/editor/extension-host.js', 'utf8');
  const messages = [];
  const self = { postMessage(m) { messages.push(m); }, onmessage: null };
  const ctx = { self, console, setTimeout, clearTimeout, Promise, TextDecoder, JSON, Object, Array, String, Error, RegExp, encodeURI };
  ctx.globalThis = ctx; vm.createContext(ctx); vm.runInContext(src, ctx);
  return { self, messages, ctx };
}
const h = boot();
// Activate an extension that registers its provider asynchronously, well after the popup asked.
const src = `
const vscode = require('vscode');
function activate(context) {
  setTimeout(function () {
    context.subscriptions.push(vscode.window.registerWebviewViewProvider('late.sidebar', {
      resolveWebviewView: function (v) { v.webview.html = '<h1>LATE VIEW</h1>'; }
    }));
  }, 100);
}
exports.activate = activate;`;
h.self.onmessage({data:{type:'activate', id:'late.ext', path:'/tmp/x', source:src, storage:'/tmp/s', globalStorage:'/tmp/g', logs:'/tmp/l', manifest:{}}});
setTimeout(() => {
  h.messages.length = 0;
  h.self.onmessage({data:{type:'open-view', extensionId:'late.ext'}});
  setTimeout(() => {
    console.log(JSON.stringify(h.messages.filter(m => m.type === 'webview'), null, 1));
  }, 250);
}, 20);
