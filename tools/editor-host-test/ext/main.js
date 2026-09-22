const vscode = require('vscode');
const cp = require('child_process');
const fs = require('fs');
const path = require('path');

exports.activate = function (context) {
  const report = {
    hasSubscriptions: Array.isArray(context.subscriptions),
    extensionPath: context.extensionPath,
    globalStorageFsPath: context.globalStorageUri && context.globalStorageUri.fsPath,
    storagePath: context.storagePath,
    logPath: context.logPath,
    hasSecrets: typeof context.secrets.get === 'function',
    hasEnvCollection: typeof context.environmentVariableCollection.replace === 'function',
    manifestName: context.extension.packageJSON.name,
    globalStateSeeded: context.globalState.get('seeded', null),
    asAbsolute: context.asAbsolutePath('main.js'),
    dirname: __dirname,
    childProcess: cp.execSync('echo spawned').toString().trim(),
    canReadOwnFile: fs.existsSync(path.join(__dirname, 'package.json')),
    hasCommands: typeof vscode.commands.registerCommand === 'function',
    hasWindow: typeof vscode.window.showInformationMessage === 'function',
    hasWorkspace: typeof vscode.workspace.getConfiguration === 'function'
  };
  context.globalState.update('seeded', 'yes');
  vscode.commands.registerCommand('probe.hello', function () { return 'hi'; });
  fs.writeFileSync(path.join(__dirname, '..', 'report.json'), JSON.stringify(report, null, 2));
};
exports.deactivate = function () {};
