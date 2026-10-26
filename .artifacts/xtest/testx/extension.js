const vscode = require('vscode');
function activate(context) {
  const provider = {
    resolveWebviewView(view) {
      view.webview.options = { enableScripts: true };
      view.webview.html = '<!doctype html><html><body style="background:#111;color:#0f0;font:16px sans-serif;padding:20px"><h2>PROBE VIEW</h2><p id="t">rendered</p><script>document.getElementById("t").textContent="script ran";</script></body></html>';
    }
  };
  context.subscriptions.push(vscode.window.registerWebviewViewProvider('viewprobe.sidebar', provider));
  context.subscriptions.push(vscode.commands.registerCommand('viewprobe.open', () => {
    vscode.window.showInformationMessage('probe command ran');
  }));
}
exports.activate = activate;
