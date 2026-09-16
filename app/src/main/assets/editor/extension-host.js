/*
 * The `vscode` API shim, and the message pump around it.
 *
 * TWO HOSTS LOAD THIS FILE. As a Web Worker it is the web-extension host, the model vscode.dev
 * uses: no Node, no filesystem, and a misbehaving extension cannot freeze the editor. Prism's Node
 * host (`node-extension-host.js`) loads the same source with a synthetic `self` and supplies a real
 * `require` through `self.prismRequire`, so extensions that need Node get Node and there is still
 * only one implementation of the API to keep correct.
 *
 * WHICH EXTENSION GOES WHERE. An extension declaring `browser` in its manifest was built for the
 * Worker and runs there. One declaring `main` is a Node extension and goes to the Node host, where
 * `fs`, `child_process` and native addons exist. What still does not work is anything needing a UI
 * surface Prism has no equivalent for -- webview panels, tree views, debug adapters -- and those
 * throw by name rather than silently doing nothing.
 *
 * THE `vscode` OBJECT BELOW IS A SHIM, not the real API. It covers what extensions actually reach
 * for; anything missing throws a named error that says so, which is far easier to act on than an
 * undefined-is-not-a-function from inside minified extension code.
 */
'use strict';

var listeners = Object.create(null);     // event name -> handlers
var commands = Object.create(null);      // command id -> handler
var providers = { completion: [], hover: [] };
var activated = Object.create(null);     // extension id -> exports
var nextCallId = 1;
var pendingCalls = Object.create(null);

// ---------------------------------------------------------------- main-thread calls

function callMain(method, payload) {
  return new Promise(function (resolve) {
    var id = nextCallId++;
    pendingCalls[id] = resolve;
    self.postMessage({ type: 'call', id: id, method: method, payload: payload });
  });
}

function emit(name, value) {
  (listeners[name] || []).forEach(function (fn) {
    try { fn(value); } catch (e) { report(e); }
  });
}

function report(e) {
  self.postMessage({
    type: 'error',
    message: (e && e.message) || String(e),
    stack: (e && e.stack) || ''
  });
}

// ---------------------------------------------------------------- vscode shim

function Disposable(fn) { this._fn = fn; }
Disposable.prototype.dispose = function () { if (this._fn) { this._fn(); this._fn = null; } };
Disposable.from = function () {
  var items = Array.prototype.slice.call(arguments);
  return new Disposable(function () { items.forEach(function (d) { d && d.dispose && d.dispose(); }); });
};

function EventEmitter() {
  var handlers = [];
  this.event = function (handler) {
    handlers.push(handler);
    return new Disposable(function () {
      var i = handlers.indexOf(handler);
      if (i >= 0) handlers.splice(i, 1);
    });
  };
  this.fire = function (value) {
    handlers.slice().forEach(function (h) { try { h(value); } catch (e) { report(e); } });
  };
  this.dispose = function () { handlers.length = 0; };
}

function Position(line, character) { this.line = line; this.character = character; }
Position.prototype.with = function (line, character) {
  return new Position(line === undefined ? this.line : line,
                      character === undefined ? this.character : character);
};
Position.prototype.translate = function (dl, dc) {
  return new Position(this.line + (dl || 0), this.character + (dc || 0));
};
Position.prototype.isBefore = function (other) {
  return this.line < other.line || (this.line === other.line && this.character < other.character);
};

function Range(startLine, startChar, endLine, endChar) {
  if (startLine instanceof Position) {
    this.start = startLine; this.end = startChar;
  } else {
    this.start = new Position(startLine, startChar);
    this.end = new Position(endLine, endChar);
  }
  this.isEmpty = this.start.line === this.end.line && this.start.character === this.end.character;
  this.isSingleLine = this.start.line === this.end.line;
}

function Selection(a, b, c, d) {
  Range.call(this, a, b, c, d);
  this.anchor = this.start;
  this.active = this.end;
}
Selection.prototype = Object.create(Range.prototype);

function Uri(scheme, authority, path) {
  this.scheme = scheme || 'file';
  this.authority = authority || '';
  this.path = path || '';
  this.fsPath = this.path;
  this.query = '';
  this.fragment = '';
}
Uri.prototype.toString = function () { return this.scheme + '://' + this.authority + this.path; };
Uri.file = function (path) { return new Uri('file', '', path); };
Uri.parse = function (value) {
  var m = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^/]*)(\/.*)?$/.exec(String(value));
  return m ? new Uri(m[1], m[2], m[3] || '') : new Uri('file', '', String(value));
};
Uri.joinPath = function (base) {
  var parts = Array.prototype.slice.call(arguments, 1);
  return new Uri(base.scheme, base.authority, (base.path + '/' + parts.join('/')).replace(/\/+/g, '/'));
};

function CompletionItem(label, kind) {
  this.label = label;
  this.kind = kind;
  this.insertText = label;
}

function MarkdownString(value) {
  this.value = value || '';
  this.isTrusted = false;
  this.appendText = function (v) { this.value += v; return this; };
  this.appendMarkdown = function (v) { this.value += v; return this; };
  this.appendCodeblock = function (code, lang) {
    this.value += '\n```' + (lang || '') + '\n' + code + '\n```\n';
    return this;
  };
}

function Hover(contents, range) { this.contents = [].concat(contents); this.range = range; }

var CompletionItemKind = {
  Text: 0, Method: 1, Function: 2, Constructor: 3, Field: 4, Variable: 5, Class: 6,
  Interface: 7, Module: 8, Property: 9, Unit: 10, Value: 11, Enum: 12, Keyword: 13,
  Snippet: 14, Color: 15, File: 16, Reference: 17, Folder: 18, EnumMember: 19,
  Constant: 20, Struct: 21, Event: 22, Operator: 23, TypeParameter: 24
};

function unsupported(name) {
  return function () {
    throw new Error(
      'Prism Editor: the VS Code API "' + name + '" is not implemented in this host yet.'
    );
  };
}

var vscode = {
  version: '1.95.0',
  Disposable: Disposable,
  EventEmitter: EventEmitter,
  Position: Position,
  Range: Range,
  Selection: Selection,
  Uri: Uri,
  CompletionItem: CompletionItem,
  CompletionItemKind: CompletionItemKind,
  MarkdownString: MarkdownString,
  Hover: Hover,

  commands: {
    registerCommand: function (id, handler) {
      commands[id] = handler;
      self.postMessage({ type: 'command-registered', id: id });
      return new Disposable(function () { delete commands[id]; });
    },
    registerTextEditorCommand: function (id, handler) {
      return vscode.commands.registerCommand(id, handler);
    },
    executeCommand: function (id) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (commands[id]) return Promise.resolve(commands[id].apply(null, args));
      // Not ours: it may be one of Monaco's, which the main thread can run.
      return callMain('executeCommand', { id: id, args: args });
    },
    getCommands: function () { return Promise.resolve(Object.keys(commands)); }
  },

  window: {
    showInformationMessage: function (message) { return callMain('message', { level: 'info', message: String(message) }); },
    showWarningMessage: function (message) { return callMain('message', { level: 'warn', message: String(message) }); },
    showErrorMessage: function (message) { return callMain('message', { level: 'error', message: String(message) }); },
    showInputBox: function (options) { return callMain('inputBox', options || {}); },
    showQuickPick: function (items, options) {
      return callMain('quickPick', { items: items, options: options || {} });
    },
    createOutputChannel: function (name) {
      return {
        name: name,
        append: function (v) { callMain('output', { channel: name, text: String(v) }); },
        appendLine: function (v) { callMain('output', { channel: name, text: String(v) + '\n' }); },
        clear: function () { callMain('output', { channel: name, clear: true }); },
        show: function () { callMain('output', { channel: name, show: true }); },
        hide: function () {}, dispose: function () {}
      };
    },
    setStatusBarMessage: function (text) {
      callMain('status', { text: String(text) });
      return new Disposable(function () {});
    },
    createStatusBarItem: function () {
      var item = {
        text: '', tooltip: '', command: undefined,
        show: function () { callMain('status', { text: String(item.text) }); },
        hide: function () {}, dispose: function () {}
      };
      return item;
    },
    get activeTextEditor() { return currentEditor; },
    onDidChangeActiveTextEditor: function (h) { return subscribe('activeEditor', h); },
    showTextDocument: function (doc) { return callMain('showDocument', { uri: doc && doc.uri && doc.uri.toString() }); },
    createTerminal: unsupported('window.createTerminal'),
    createWebviewPanel: unsupported('window.createWebviewPanel'),
    createTreeView: unsupported('window.createTreeView')
  },

  workspace: {
    workspaceFolders: [],
    name: undefined,
    getConfiguration: function (section) {
      return {
        get: function (key, fallback) {
          var full = section ? section + '.' + key : key;
          return configuration[full] !== undefined ? configuration[full] : fallback;
        },
        has: function (key) {
          var full = section ? section + '.' + key : key;
          return configuration[full] !== undefined;
        },
        update: function (key, value) {
          var full = section ? section + '.' + key : key;
          configuration[full] = value;
          return callMain('configure', { key: full, value: value });
        },
        inspect: function () { return undefined; }
      };
    },
    onDidChangeConfiguration: function (h) { return subscribe('configuration', h); },
    onDidChangeTextDocument: function (h) { return subscribe('textDocumentChanged', h); },
    onDidOpenTextDocument: function (h) { return subscribe('textDocumentOpened', h); },
    onDidSaveTextDocument: function (h) { return subscribe('textDocumentSaved', h); },
    openTextDocument: function (target) { return callMain('openDocument', { target: String(target) }); },
    fs: {
      readFile: function (uri) { return callMain('readFile', { path: uri.fsPath || uri.path }); },
      writeFile: function (uri, content) {
        return callMain('writeFile', { path: uri.fsPath || uri.path, content: decodeBytes(content) });
      },
      readDirectory: function (uri) { return callMain('readDirectory', { path: uri.fsPath || uri.path }); }
    },
    asRelativePath: function (p) { return String(p); },
    findFiles: function (pattern) { return callMain('findFiles', { pattern: String(pattern) }); }
  },

  languages: {
    registerCompletionItemProvider: function (selector, provider, ...triggers) {
      var entry = { selector: selector, provider: provider, triggers: triggers };
      providers.completion.push(entry);
      return new Disposable(function () {
        var i = providers.completion.indexOf(entry);
        if (i >= 0) providers.completion.splice(i, 1);
      });
    },
    registerHoverProvider: function (selector, provider) {
      var entry = { selector: selector, provider: provider };
      providers.hover.push(entry);
      return new Disposable(function () {
        var i = providers.hover.indexOf(entry);
        if (i >= 0) providers.hover.splice(i, 1);
      });
    },
    createDiagnosticCollection: function (name) {
      return {
        name: name,
        set: function (uri, diagnostics) {
          callMain('diagnostics', {
            path: uri && (uri.fsPath || uri.path),
            items: (diagnostics || []).map(function (d) {
              return {
                message: d.message,
                severity: d.severity,
                line: d.range && d.range.start ? d.range.start.line : 0,
                column: d.range && d.range.start ? d.range.start.character : 0
              };
            })
          });
        },
        delete: function () {}, clear: function () {}, dispose: function () {}
      };
    },
    registerDefinitionProvider: function () { return new Disposable(function () {}); },
    registerDocumentFormattingEditProvider: function () { return new Disposable(function () {}); }
  },

  env: {
    appName: 'Prism Editor',
    language: 'en',
    clipboard: {
      readText: function () { return callMain('clipboardRead', {}); },
      writeText: function (text) { return callMain('clipboardWrite', { text: String(text) }); }
    },
    openExternal: function (uri) { return callMain('openExternal', { uri: String(uri) }); }
  },

  extensions: {
    getExtension: function (id) { return activated[id] ? { id: id, isActive: true, exports: activated[id] } : undefined; },
    all: []
  },

  ExtensionMode: { Production: 1, Development: 2, Test: 3 },
  ViewColumn: { Active: -1, One: 1, Two: 2, Three: 3 },
  StatusBarAlignment: { Left: 1, Right: 2 },
  DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2, Hint: 3 },
  ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 }
};

var configuration = Object.create(null);
var currentEditor = undefined;

function subscribe(name, handler) {
  (listeners[name] = listeners[name] || []).push(handler);
  return new Disposable(function () {
    var list = listeners[name] || [];
    var i = list.indexOf(handler);
    if (i >= 0) list.splice(i, 1);
  });
}

function decodeBytes(content) {
  if (typeof content === 'string') return content;
  try { return new TextDecoder().decode(content); } catch (e) { return String(content); }
}

// ---------------------------------------------------------------- module loading

/**
 * The `require` an extension sees.
 *
 * `vscode` always resolves to the shim. Everything else depends on the host: the Node host installs
 * `self.prismRequire`, a real CommonJS require rooted at the extension's own directory, so `fs`,
 * `child_process` and the extension's own `node_modules` all work. In the Worker there is no such
 * hook and the request throws by name -- an extension reaching for `fs` there is telling us it is
 * not a web extension, and failing loudly at that line is far more useful than handing it an empty
 * object and letting it fail somewhere unrecognisable ten frames later.
 */
function makeRequire(extensionId) {
  return function (name) {
    if (name === 'vscode') return vscode;
    if (typeof self.prismRequire === 'function') return self.prismRequire(extensionId, name);
    throw new Error(
      'Extension "' + extensionId + '" requires the Node module "' + name + '". ' +
      'This host runs web extensions only, which cannot use Node modules.'
    );
  };
}

function activateExtension(id, source, context) {
  var api;
  if (typeof self.prismLoadModule === 'function') {
    // The Node host loads through Node's own module machinery instead, so the extension gets real
    // `__dirname`, relative requires, and its bundled `node_modules`. Evaluating its source in a
    // bare Function as the Worker does would break all three.
    api = self.prismLoadModule(id, source);
  } else {
    var module = { exports: {} };
    var fn = new Function('require', 'module', 'exports', 'self', 'globalThis', source);
    fn(makeRequire(id), module, module.exports, self, self);
    api = module.exports;
  }

  if (api && typeof api.activate === 'function') {
    var result = api.activate(context);
    activated[id] = api;
    return Promise.resolve(result);
  }
  activated[id] = api || {};
  return Promise.resolve(null);
}

// ---------------------------------------------------------------- message pump

self.onmessage = function (event) {
  var msg = event.data || {};

  switch (msg.type) {
    case 'activate':
      try {
        var context = {
          subscriptions: [],
          extensionPath: msg.path || '',
          extensionUri: Uri.file(msg.path || ''),
          globalState: memento('global-' + msg.id),
          workspaceState: memento('workspace-' + msg.id),
          extensionMode: vscode.ExtensionMode.Production,
          asAbsolutePath: function (rel) { return (msg.path || '') + '/' + rel; }
        };
        activateExtension(msg.id, msg.source, context)
          .then(function () { self.postMessage({ type: 'activated', id: msg.id }); })
          .catch(function (e) { self.postMessage({ type: 'activation-failed', id: msg.id, message: String(e && e.message || e) }); });
      } catch (e) {
        self.postMessage({ type: 'activation-failed', id: msg.id, message: String(e && e.message || e) });
      }
      break;

    case 'call-result':
      var resolver = pendingCalls[msg.id];
      if (resolver) { delete pendingCalls[msg.id]; resolver(msg.value); }
      break;

    case 'run-command':
      var handler = commands[msg.id];
      if (!handler) {
        self.postMessage({ type: 'command-missing', id: msg.id });
        break;
      }
      try {
        Promise.resolve(handler.apply(null, msg.args || []))
          .then(function (v) { self.postMessage({ type: 'command-done', id: msg.id, value: v === undefined ? null : v }); })
          .catch(function (e) { report(e); });
      } catch (e) { report(e); }
      break;

    case 'complete':
      collectCompletions(msg).then(function (items) {
        self.postMessage({ type: 'completions', id: msg.id, items: items });
      });
      break;

    case 'event':
      emit(msg.name, msg.value);
      break;
  }
};

function memento(scope) {
  var store = Object.create(null);
  return {
    get: function (key, fallback) { return store[key] === undefined ? fallback : store[key]; },
    update: function (key, value) { store[key] = value; return Promise.resolve(); },
    keys: function () { return Object.keys(store); }
  };
}

/** Asks every registered completion provider and flattens what they return. */
function collectCompletions(msg) {
  var document = {
    languageId: msg.language,
    uri: Uri.file(msg.path || ''),
    fileName: msg.path || '',
    getText: function () { return msg.text || ''; },
    lineAt: function (line) {
      var lines = String(msg.text || '').split('\n');
      return { text: lines[line] || '', lineNumber: line };
    },
    getWordRangeAtPosition: function () { return undefined; },
    offsetAt: function () { return 0; }
  };
  var position = new Position((msg.line || 1) - 1, (msg.column || 1) - 1);

  var matching = providers.completion.filter(function (entry) {
    return matchesSelector(entry.selector, msg.language);
  });

  return Promise.all(matching.map(function (entry) {
    try {
      return Promise.resolve(entry.provider.provideCompletionItems(document, position, null, {}))
        .catch(function () { return null; });
    } catch (e) { return Promise.resolve(null); }
  })).then(function (results) {
    var out = [];
    results.forEach(function (result) {
      if (!result) return;
      var items = Array.isArray(result) ? result : (result.items || []);
      items.forEach(function (item) {
        out.push({
          label: typeof item.label === 'string' ? item.label : (item.label && item.label.label) || '',
          insert: typeof item.insertText === 'string' ? item.insertText : (item.insertText && item.insertText.value) || undefined,
          detail: item.detail || '',
          doc: typeof item.documentation === 'string' ? item.documentation : (item.documentation && item.documentation.value) || '',
          kind: item.kind
        });
      });
    });
    return out;
  });
}

function matchesSelector(selector, language) {
  if (!selector) return true;
  var list = Array.isArray(selector) ? selector : [selector];
  return list.some(function (s) {
    if (typeof s === 'string') return s === language || s === '*';
    return !s.language || s.language === language || s.language === '*';
  });
}

// Handed to the Node host, which needs the same `vscode` object its own module loader will return
// for `require('vscode')`. Harmless in a Worker, where nothing reads it.
self.prismVscode = vscode;
self.prismMakeRequire = makeRequire;

self.postMessage({ type: 'host-ready' });
