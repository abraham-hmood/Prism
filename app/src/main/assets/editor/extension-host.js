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
 * `fs`, `child_process` and native addons exist. Webview panels and webview view providers are
 * supported: their HTML is posted to Kotlin, which renders it in the extension's floating popup,
 * so an extension's own interface appears and follows every reassignment of `webview.html`. What
 * still does not work is a UI surface Prism has no equivalent for -- tree views, debug adapters --
 * and those throw by name rather than silently doing nothing.
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

// Webviews. A panel and a resolved webview view are the same thing to Prism: an id, an owner, the
// HTML the extension last set, and a message channel back into the extension. The popup renders
// whichever one the extension is currently showing.
var webviews = Object.create(null);          // panel id -> record
var webviewProviders = Object.create(null);  // owner|viewId -> provider registration
var commandOwners = Object.create(null);     // command id -> extension id
var currentExtensionId = null;               // who is registering right now
var nextWebviewId = 1;

// Extensions whose popup is waiting for a view. Registration is not always synchronous with
// `activate`: an extension may await a socket, a language server or a settings read before it
// calls `registerWebviewViewProvider`. The popup asked once, at the only moment it could, and a
// provider that appeared a second later used to have nobody left to tell. The request is kept
// here until a view is actually produced, and a provider registered in the meantime is resolved
// immediately -- which is exactly what VS Code does when the view becomes visible.
var pendingViewRequests = Object.create(null);  // extension id -> timer handle

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

// ---------------------------------------------------------------- webviews

/**
 * Tells the page that a webview's state changed.
 *
 * The HTML travels with every message rather than being fetched on demand, because only the host
 * holds it -- a webview's content is a string in the extension's memory, not a file Kotlin can
 * read. One message per change is also what makes the popup follow the extension: when it swaps
 * one view for another, the new HTML arrives without the popup asking for anything.
 */
function postWebview(record, action) {
  if (action === 'html' && record.webview && record.webview.html) resolvePendingView(record.owner);
  self.postMessage({
    type: 'webview',
    action: action,
    panelId: record.panelId,
    extensionId: record.owner,
    kind: record.kind,
    title: record.title,
    viewType: record.viewType,
    html: record.webview ? record.webview.html : ''
  });
}

/**
 * The `Webview` object an extension receives.
 *
 * `html` is an accessor, not a field, because that is the only way to notice an assignment: a
 * webview is rendered by setting `panel.webview.html = ...`, often repeatedly as the extension
 * re-renders. A plain property would leave the popup showing the first render forever.
 */
function createWebview(record) {
  var handlers = [];
  var html = '';

  var webview = {
    options: { enableScripts: true, localResourceRoots: [] },
    cspSource: 'file:',

    /**
     * A file the webview may load.
     *
     * Returns a URI object rather than a bare string because extensions call `.toString()` on it,
     * interpolate it into HTML, and occasionally read `.fsPath`. Extension files are unpacked inside
     * Prism's own storage and the popup WebView can read them directly, so no asset translation is
     * needed -- the path is already the one Android needs.
     */
    asWebviewUri: function (uri) {
      var fsPath = (uri && uri.fsPath) || String(uri || '').replace(/^file:\/\//, '');
      if (fsPath.charAt(0) !== '/') fsPath = '/' + fsPath;
      return {
        scheme: 'file',
        path: fsPath,
        fsPath: fsPath,
        toString: function () {
          return 'file://' + encodeURI(fsPath)
            .replace(/\?/g, '%3F')
            .replace(/#/g, '%23');
        }
      };
    },

    postMessage: function (message) {
      self.postMessage({ type: 'webview-to-page', panelId: record.panelId, payload: message });
      return Promise.resolve(true);
    },

    onDidReceiveMessage: function (handler) {
      handlers.push(handler);
      return new Disposable(function () {
        var i = handlers.indexOf(handler);
        if (i >= 0) handlers.splice(i, 1);
      });
    },

    /** Delivery from the popup. `json` is the string the page forwarded from the rendered view. */
    receive: function (json) {
      var value;
      try { value = typeof json === 'string' ? JSON.parse(json) : json; }
      catch (e) { value = json; }
      handlers.slice().forEach(function (handler) {
        try { handler(value); } catch (e) { report(e); }
      });
    }
  };

  Object.defineProperty(webview, 'html', {
    get: function () { return html; },
    set: function (value) {
      html = value === undefined || value === null ? '' : String(value);
      if (!record.disposed) postWebview(record, 'html');
    }
  });

  return webview;
}

function newWebviewRecord(kind, owner, viewType, title) {
  var record = {
    kind: kind,
    panelId: (kind === 'view' ? 'view-' : 'panel-') + (nextWebviewId++),
    owner: owner || 'unknown',
    viewType: String(viewType || ''),
    title: String(title || viewType || 'Extension'),
    disposed: false,
    visible: true,
    active: true,
    disposeHandlers: []
  };
  record.webview = createWebview(record);
  webviews[record.panelId] = record;
  return record;
}

function disposeWebview(record) {
  if (record.disposed) return;
  record.disposed = true;
  delete webviews[record.panelId];
  postWebview(record, 'dispose');
  record.disposeHandlers.slice().forEach(function (handler) {
    try { handler(); } catch (e) { report(e); }
  });
}

/**
 * `vscode.window.createWebviewPanel`. One panel is one view the popup can show.
 *
 * [owner] exists because `currentExtensionId` is only reliable for work that happens synchronously
 * inside `activate` or a command. An extension that builds its panel from a timer, a socket callback
 * or an awaited promise would otherwise be attributed to whichever extension last activated -- so
 * its view would be filed under the wrong popup and never appear. The per-extension `vscode` object
 * ([vscodeFor]) passes the real owner, and this falls back to the old global for the Worker path.
 */
function createWebviewPanel(viewType, title, showOptions, options, owner) {
  var record = newWebviewRecord('panel', owner || currentExtensionId, viewType, title);
  if (options && options.enableScripts === false) record.webview.options = {};
  // A panel created after the popup asked for a view is the view it was waiting for. The HTML
  // that follows arrives as its own message; this only keeps the request from timing out first.
  if (pendingViewRequests[record.owner]) postWebview(record, 'open');

  var panel = {
    viewType: record.viewType,
    webview: record.webview,
    visible: true,
    active: true,
    iconPath: options && options.iconPath,
    onDidDispose: function (handler) {
      record.disposeHandlers.push(handler);
      return new Disposable(function () {
        var i = record.disposeHandlers.indexOf(handler);
        if (i >= 0) record.disposeHandlers.splice(i, 1);
      });
    },
    // Prism has no side-by-side columns, and a view that is open is visible. The event is here
    // because extensions subscribe unconditionally.
    onDidChangeViewState: function () { return new Disposable(function () {}); },
    reveal: function () {
      record.visible = true;
      postWebview(record, 'open');
    },
    dispose: function () { disposeWebview(record); }
  };
  Object.defineProperty(panel, 'title', {
    get: function () { return record.title; },
    set: function (value) {
      record.title = String(value === undefined || value === null ? '' : value);
      if (!record.disposed) postWebview(record, 'title');
    }
  });

  postWebview(record, 'open');
  return panel;
}

/**
 * `vscode.window.registerWebviewViewProvider`.
 *
 * The provider is only asked to build its view when something wants to show it -- opening the
 * extension's popup, in practice. That matches VS Code: a view that has never been visible has no
 * HTML and costs nothing.
 */
/** Stops waiting for a view from [extensionId]. */
function resolvePendingView(extensionId) {
  var timer = pendingViewRequests[extensionId];
  if (timer !== undefined) {
    clearTimeout(timer);
    delete pendingViewRequests[extensionId];
  }
}

function registerWebviewViewProvider(viewId, provider, owner) {
  var id = String(viewId);
  var extensionOwner = owner || currentExtensionId || 'unknown';
  var key = extensionOwner + '|' + id;
  var previous = webviewProviders[key];
  // A reactivated extension registers again. Keeping the old record would leave a stale view
  // competing with the new one for "the newest view this extension has".
  if (previous && previous.record) disposeWebview(previous.record);
  webviewProviders[key] = {
    owner: extensionOwner,
    viewId: id,
    provider: provider,
    record: null
  };
  // The popup opened before this provider existed. Resolve it now instead of making the user
  // close and reopen the extension -- which is what "No view yet" used to mean.
  if (pendingViewRequests[extensionOwner]) {
    resolveWebviewView(webviewProviders[key]);
  }
  return new Disposable(function () {
    var entry = webviewProviders[key];
    if (!entry) return;
    if (entry.record) disposeWebview(entry.record);
    delete webviewProviders[key];
  });
}

/** Builds (once) and shows the view behind a provider registration. */
function resolveWebviewView(entry) {
  if (entry.record && !entry.record.disposed) {
    postWebview(entry.record, 'open');
    return;
  }

  var record = newWebviewRecord('view', entry.owner, entry.viewId, entry.viewId);
  var view = {
    viewType: record.viewType,
    title: record.title,
    description: undefined,
    badge: undefined,
    visible: true,
    webview: record.webview,
    show: function () {
      record.visible = true;
      postWebview(record, 'open');
    },
    onDidChangeVisibility: function () { return new Disposable(function () {}); },
    onDidDispose: function (handler) {
      record.disposeHandlers.push(handler);
      return new Disposable(function () {});
    },
    onDidChangeTitle: function () { return new Disposable(function () {}); },
    onDidChangeBadge: function () { return new Disposable(function () {}); }
  };
  entry.record = record;

  try {
    var result = entry.provider.resolveWebviewView(view, { state: null }, {
      isCancellationRequested: false,
      onCancellationRequested: function () { return new Disposable(function () {}); }
    });
    if (result && typeof result.then === 'function') {
      result.then(
        function () { postWebview(record, 'open'); },
        function (e) { report(e); }
      );
    }
  } catch (e) { report(e); }

  // Sent immediately as well: most providers set `webview.html` synchronously, and waiting for a
  // promise that may never resolve would leave the popup empty for no reason.
  postWebview(record, 'open');
}

/**
 * Shows whatever this extension is currently displaying.
 *
 * THE NEWEST PANEL WINS. An extension that switches views disposes one panel and creates another,
 * and "render the main view, then render whatever it changed to" means following the newest panel
 * rather than pinning the first one. A view provider is resolved only when there is no panel.
 */
function openExtensionWebview(extensionId) {
  // Remember the request for a while. An extension that registers its provider after an await --
  // most of them do -- would otherwise post `none` and the popup would sit on its placeholder
  // even though a view was about to exist. `resolvePendingView` clears this the moment HTML
  // arrives; the timeout is only so a genuinely view-less extension stops being retried.
  resolvePendingView(extensionId);
  pendingViewRequests[extensionId] = setTimeout(function () {
    delete pendingViewRequests[extensionId];
  }, 20000);

  var records = Object.keys(webviews).map(function (key) { return webviews[key]; });
  var alive = records.filter(function (record) {
    return record.owner === extensionId && !record.disposed;
  });
  var panels = alive.filter(function (record) { return record.kind === 'panel'; });
  var views = alive.filter(function (record) { return record.kind === 'view'; });

  // Anything that has actually drawn something is what the extension is showing right now, and
  // the newest of those wins -- that is how "render the main view, then follow it to the next
  // thing the extension switches to" works.
  var drawn = alive.filter(function (record) {
    return record.webview && record.webview.html;
  });
  if (drawn.length) {
    resolvePendingView(extensionId);
    postWebview(drawn[drawn.length - 1], 'open');
    return;
  }

  // Nothing has drawn yet. A webview VIEW PROVIDER is still the extension's main view, and
  // resolving it is exactly what VS Code does when that view becomes visible. This is the case
  // the old order missed: an existing but still-empty panel short-circuited the provider, so an
  // extension whose panel was created before its first render looked like it had no view at all.
  var keys = Object.keys(webviewProviders).filter(function (key) {
    return webviewProviders[key].owner === extensionId;
  });
  var unresolved = keys.filter(function (key) {
    var entry = webviewProviders[key];
    return !entry.record || entry.record.disposed;
  });
  var target = unresolved.length ? unresolved[0] : keys[0];
  if (target) {
    resolveWebviewView(webviewProviders[target]);
    return;
  }

  // An empty panel or view still exists, and its next render arrives as a `html` message. Report
  // it so the popup keeps listening instead of concluding the extension has no interface.
  if (panels.length) {
    postWebview(panels[panels.length - 1], 'open');
    return;
  }
  if (views.length) {
    postWebview(views[views.length - 1], 'open');
    return;
  }

  // If a provider is registered later it resolves itself and clears the pending request; until
  // then the popup is told to keep its placeholder rather than pretend the extension has no UI.
  self.postMessage({ type: 'webview', action: 'waiting', extensionId: extensionId });
}

/**
 * Registers a command against an explicit owner.
 *
 * The owner is not decoration: a command that opens a webview sets `currentExtensionId` from
 * `commandOwners` before running, which is how the panel it creates ends up in that extension's
 * popup. A command registered asynchronously after activation used to have a null owner and would
 * take the panel of whoever happened to be active.
 */
function registerCommandFor(owner, id, handler) {
  commands[id] = handler;
  commandOwners[id] = owner || null;
  self.postMessage({ type: 'command-registered', id: id });
  return new Disposable(function () { delete commands[id]; delete commandOwners[id]; });
}

// ---------------------------------------------------------------- notebooks
//
// The notebook API is almost entirely UI -- controllers, renderers, serializers -- and Prism has
// no notebook surface. The two classes here exist because they are NAME-SPACE OBJECTS: extensions
// touch `vscode.NotebookCellOutputItem.error` at module scope, before `activate`, to read the MIME
// type of an error output. Leaving the class undefined made a minified bundle throw on its very
// first line (`Cannot read properties of undefined (reading 'error')`) and the extension never
// activated at all. A working value object costs nothing and gets activation past that line.

function NotebookCellOutputItem(mime, value) {
  this.mime = mime;
  this.value = value instanceof Uint8Array ? value : new Uint8Array(value || 0);
}
NotebookCellOutputItem.text = function (value, mime) {
  return new NotebookCellOutputItem(mime || 'text/plain', new TextEncoder().encode(String(value)));
};
NotebookCellOutputItem.json = function (value, mime) {
  return new NotebookCellOutputItem(mime || 'application/json', new TextEncoder().encode(JSON.stringify(value)));
};
NotebookCellOutputItem.error = function (error) {
  var payload = {
    name: (error && error.name) || 'Error',
    message: (error && error.message) || String(error),
    stack: (error && error.stack) || ''
  };
  return new NotebookCellOutputItem(
    'application/vnd.code.notebook.error',
    new TextEncoder().encode(JSON.stringify(payload))
  );
};
NotebookCellOutputItem.stdout = function (value) {
  return new NotebookCellOutputItem('application/vnd.code.notebook.stdout', new TextEncoder().encode(String(value)));
};
NotebookCellOutputItem.stderr = function (value) {
  return new NotebookCellOutputItem('application/vnd.code.notebook.stderr', new TextEncoder().encode(String(value)));
};

function NotebookCellData(kind, value, languageId) {
  this.kind = kind;
  this.value = value;
  this.languageId = languageId;
  this.outputs = [];
  this.metadata = {};
  this.executionSummary = undefined;
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
  RelativePattern: function (base, pattern) {
    this.baseUri = base && base.uri ? base.uri : base;
    this.pattern = String(pattern);
    this.base = base;
    this.baseUri = this.baseUri || base;
  },
  NotebookCellOutputItem: NotebookCellOutputItem,
  NotebookCellData: NotebookCellData,
  NotebookCellKind: { Markup: 1, Code: 2 },

  commands: {
    registerCommand: function (id, handler) {
      return registerCommandFor(currentExtensionId, id, handler);
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
    createOutputChannel: function (name, options) {
      // `{ log: true }` requests a LogOutputChannel, and an extension that asked for one calls
      // `.info()`, `.warn()` and `.error()` on it immediately. Those were missing, so the first
      // line of a real extension's logging threw "Cannot read properties of undefined (reading
      // 'error')" and activation failed before it registered its sidebar. The level variants are
      // the same append with a prefix; there is no separate channel type to maintain.
      var write = function (level) {
        return function (v) {
          callMain('output', {
            channel: name,
            level: level,
            text: String(v) + '\n'
          });
        };
      };
      return {
        name: name,
        logLevel: 2,          // LogLevel.Info
        append: function (v) { callMain('output', { channel: name, text: String(v) }); },
        appendLine: function (v) { callMain('output', { channel: name, text: String(v) + '\n' }); },
        trace: write('trace'),
        debug: write('debug'),
        info: write('info'),
        warn: write('warn'),
        error: write('error'),
        clear: function () { callMain('output', { channel: name, clear: true }); },
        show: function () { callMain('output', { channel: name, show: true }); },
        hide: function () {}, dispose: function () {},
        replace: function (v) { callMain('output', { channel: name, text: String(v), replace: true }); },
        onDidChangeLogLevel: function () { return new Disposable(function () {}); }
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
    // The tab API is read at module scope by extensions that remember which tabs were open. It is
    // read-only here -- Prism has one editor surface, not VS Code's tab strip -- but `all` and the
    // two events have to exist, because `tabGroups.all.flatMap(...)` runs before anything else.
    tabGroups: {
      all: [],
      activeTabGroup: undefined,
      onDidChangeTabs: function (h) { return subscribe('tabs', h); },
      onDidChangeTabGroups: function (h) { return subscribe('tabGroups', h); },
      close: function () { return Promise.resolve(true); }
    },
    tabs: [],
    onDidChangeActiveTextEditor: function (h) { return subscribe('activeEditor', h); },
    onDidChangeVisibleTextEditors: function (h) { return subscribe('visibleEditors', h); },
    onDidChangeTextEditorSelection: function (h) { return subscribe('textEditorSelection', h); },
    onDidChangeWindowState: function (h) { return subscribe('windowState', h); },
    onDidChangeActiveColorTheme: function (h) { return subscribe('colorTheme', h); },
    showTextDocument: function (doc) { return callMain('showDocument', { uri: doc && doc.uri && doc.uri.toString() }); },
    createTerminal: unsupported('window.createTerminal'),
    // Webviews are the only extension-owned pixels the host has. A panel or a resolved view is
    // rendered in the extension's floating popup, so `panel.webview.html = ...` is what puts an
    // extension's UI on screen -- including when it replaces that HTML later.
    createWebviewPanel: createWebviewPanel,
    registerWebviewViewProvider: registerWebviewViewProvider,
    // A panel opened in a previous session cannot be restored: Prism does not persist the HTML an
    // extension drew. The registration still has to succeed, because extensions call it during
    // activation and treat a missing function as a fatal error.
    registerWebviewPanelSerializer: function () { return new Disposable(function () {}); },
    registerCustomEditorProvider: function () { return new Disposable(function () {}); },
    registerFileDecorationProvider: function () { return new Disposable(function () {}); },
    registerUriHandler: function () { return new Disposable(function () {}); },
    registerExternalUriOpener: function () { return new Disposable(function () {}); },
    registerTerminalProfileProvider: function () { return new Disposable(function () {}); },
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
    // File and selection events. Prism raises none of them yet, but every one is subscribed to
    // unconditionally by real extensions, and a missing function is a hard activation failure.
    // `subscribe` means a future emitter needs no change here.
    onDidChangeWorkspaceFolders: function (h) { return subscribe('workspaceFolders', h); },
    onWillSaveTextDocument: function (h) { return subscribe('willSaveTextDocument', h); },
    onDidCloseTextDocument: function (h) { return subscribe('textDocumentClosed', h); },
    onDidCreateFiles: function (h) { return subscribe('filesCreated', h); },
    onDidDeleteFiles: function (h) { return subscribe('filesDeleted', h); },
    onDidRenameFiles: function (h) { return subscribe('filesRenamed', h); },
    onWillCreateFiles: function (h) { return subscribe('willCreateFiles', h); },
    onWillDeleteFiles: function (h) { return subscribe('willDeleteFiles', h); },
    onWillRenameFiles: function (h) { return subscribe('willRenameFiles', h); },
    getWorkspaceFolder: function () { return undefined; },
    updateWorkspaceFolders: function () { return false; },
    // Returning a disposable rather than throwing. Registering a provider is how an extension
    // exposes a virtual scheme (`claude-code://` for Claude's own documents), and the call happens
    // during activation whether or not anything reads that scheme. Throwing here killed the whole
    // extension; a provider Prism never consults is a no-op, and the disposable keeps the
    // extension's unsubscribe path honest.
    registerFileSystemProvider: function () { return new Disposable(function () {}); },
    registerNotebookSerializer: function () { return new Disposable(function () {}); },
    createFileSystemWatcher: function () {
      return {
        ignoreCreateEvents: false,
        ignoreChangeEvents: false,
        ignoreDeleteEvents: false,
        onDidCreate: function () { return new Disposable(function () {}); },
        onDidChange: function () { return new Disposable(function () {}); },
        onDidDelete: function () { return new Disposable(function () {}); },
        dispose: function () {}
      };
    },
    applyEdit: function () { return Promise.resolve(true); },
    registerTextDocumentContentProvider: function () { return new Disposable(function () {}); },
    onDidChangeTextEditorSelection: function (h) { return subscribe('textEditorSelection', h); },
    onDidChangeVisibleTextEditors: function (h) { return subscribe('visibleTextEditors', h); },
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
/**
 * The `vscode` object one extension sees, with its attribution already attached.
 *
 * Extensions share a single shim, so the only way to know who created a panel or registered a
 * command is to capture it at the API call. Doing that per extension -- rather than with a global
 * "currently activating" id -- is what makes an extension that builds its view asynchronously keep
 * it. Everything else on the object is inherited unchanged, so this is invisible to the extension.
 */
function vscodeFor(extensionId) {
  if (typeof extensionId !== 'string' || !extensionId) return vscode;

  var scopedWindow = Object.create(vscode.window);
  scopedWindow.createWebviewPanel = function (viewType, title, showOptions, options) {
    return createWebviewPanel(viewType, title, showOptions, options, extensionId);
  };
  scopedWindow.registerWebviewViewProvider = function (viewId, provider) {
    return registerWebviewViewProvider(viewId, provider, extensionId);
  };

  var scopedCommands = Object.create(vscode.commands);
  scopedCommands.registerCommand = function (id, handler) {
    return registerCommandFor(extensionId, id, handler);
  };
  scopedCommands.registerTextEditorCommand = function (id, handler) {
    return scopedCommands.registerCommand(id, handler);
  };

  var scoped = Object.create(vscode);
  scoped.window = scopedWindow;
  scoped.commands = scopedCommands;
  return scoped;
}

function makeRequire(extensionId) {
  var api = vscodeFor(extensionId);
  return function (name) {
    if (name === 'vscode') return api;
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
      // Registration APIs attribute what they are handed to "the extension activating right now".
      // It is set before the call because `activate` runs synchronously up to its first await.
      currentExtensionId = msg.id;
      try {
        var context = buildExtensionContext(msg);
        activateExtension(msg.id, msg.source, context)
          .then(function () { self.postMessage({ type: 'activated', id: msg.id }); })
          .catch(function (e) {
            self.postMessage({
              type: 'activation-failed',
              id: msg.id,
              message: String(e && e.message || e),
              stack: String(e && e.stack || '')
            });
          });
      } catch (e) {
        self.postMessage({
          type: 'activation-failed',
          id: msg.id,
          message: String(e && e.message || e),
          stack: String(e && e.stack || '')
        });
      }
      break;

    case 'call-result':
      var resolver = pendingCalls[msg.id];
      if (resolver) { delete pendingCalls[msg.id]; resolver(msg.value); }
      break;

    case 'run-command':
      // A command that opens a panel should have it owned by the extension that registered the
      // command, not by whichever extension happened to activate last.
      if (commandOwners[msg.id]) currentExtensionId = commandOwners[msg.id];
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

    case 'open-view':
      openExtensionWebview(msg.extensionId);
      break;

    case 'from-webview':
      var target = webviews[msg.panelId];
      if (target) target.webview.receive(msg.payload);
      break;

    case 'event':
      emit(msg.name, msg.value);
      break;
  }
};

/**
 * The `ExtensionContext` handed to `activate`.
 *
 * ## Why this is a function and not an object literal
 *
 * Because it got long, and it got long for a reason worth recording. The first version had six
 * fields — subscriptions, the two mementos, the path, the mode — which is everything a *web*
 * extension touches. Desktop extensions touch far more, and they do it on the first line of
 * `activate` without checking: `context.globalStorageUri.fsPath` to find a cache directory,
 * `context.logUri` for a log file, `context.secrets.get` for a token, `context.storagePath` in
 * anything written before 2021. Every one of those was `undefined`, and reading `.fsPath` off
 * `undefined` throws before the extension has done anything at all.
 *
 * So the rule here is: if VS Code's own `ExtensionContext` has a member, this provides something
 * with the right shape, even when the thing behind it is a stub. An extension that gets a working
 * object and finds it empty degrades; an extension that gets `undefined` crashes.
 *
 * ## Storage
 *
 * The paths come from Kotlin, which is the only side that knows where the app may write. They live
 * outside the extension's own directory on purpose — uninstalling an extension should not silently
 * destroy the user's settings for it, and reinstalling should find them again. The Node host
 * creates them before activation; the Worker host cannot, and an extension that writes there will
 * fail, which is the honest behaviour for a host with no filesystem.
 */
function buildExtensionContext(msg) {
  var root = msg.path || '';
  var storage = msg.storage || '';
  var globalStorage = msg.globalStorage || '';
  var logs = msg.logs || '';

  return {
    subscriptions: [],

    extensionPath: root,
    extensionUri: Uri.file(root),
    asAbsolutePath: function (rel) {
      return (root + '/' + String(rel)).replace(/\/+/g, '/');
    },

    globalState: memento('global-' + msg.id, (msg.state || {}).global, true),
    workspaceState: memento('workspace-' + msg.id, (msg.state || {}).workspace, false),

    // Both the Uri and the deprecated string form. A great deal of published code still reads the
    // string, and VS Code kept it for exactly that reason.
    storageUri: storage ? Uri.file(storage) : undefined,
    storagePath: storage || undefined,
    globalStorageUri: globalStorage ? Uri.file(globalStorage) : undefined,
    globalStoragePath: globalStorage || undefined,
    logUri: logs ? Uri.file(logs) : undefined,
    logPath: logs || undefined,

    extensionMode: vscode.ExtensionMode.Production,

    // A secret store, in memory. NOT persisted, and that is deliberate: a real one would be a
    // promise to keep tokens safely that this host is in no position to make. An extension asking
    // for a secret gets undefined and prompts the user again, which is the correct degradation.
    secrets: {
      get: function () { return Promise.resolve(undefined); },
      store: function () { return Promise.resolve(); },
      delete: function () { return Promise.resolve(); },
      onDidChange: function () { return { dispose: function () {} }; }
    },

    // Extensions mutate this to affect terminals they spawn. Prism's terminal does not read it yet,
    // so the collection records and does nothing — which is a no-op, not a crash.
    environmentVariableCollection: (function () {
      var vars = Object.create(null);
      var collection = {
        persistent: false,
        description: '',
        replace: function (name, value) { vars[name] = { value: value, type: 1 }; },
        append: function (name, value) { vars[name] = { value: value, type: 2 }; },
        prepend: function (name, value) { vars[name] = { value: value, type: 3 }; },
        get: function (name) { return vars[name]; },
        forEach: function (fn, thisArg) {
          Object.keys(vars).forEach(function (k) { fn.call(thisArg, k, vars[k], collection); });
        },
        delete: function (name) { delete vars[name]; },
        clear: function () { vars = Object.create(null); },
        getScoped: function () { return collection; }
      };
      return collection;
    })(),

    extension: {
      id: msg.id,
      extensionPath: root,
      extensionUri: Uri.file(root),
      isActive: true,
      packageJSON: msg.manifest || {},
      exports: undefined,
      extensionKind: 1,
      activate: function () { return Promise.resolve(); }
    },

    // Added in 1.90 and already called unconditionally by a handful of AI extensions.
    languageModelAccessInformation: {
      onDidChange: function () { return { dispose: function () {} }; },
      canSendRequest: function () { return undefined; }
    }
  };
}

/**
 * A `Memento`: the key-value store `context.globalState` and `context.workspaceState` are.
 *
 * `get` is synchronous in the VS Code API and `update` returns a promise, which is the shape that
 * decides how this persists: the whole store is handed over at activation time (so `get` can answer
 * immediately) and each `update` posts a save (so nothing blocks). An extension that writes "do not
 * show this again" on Monday finds it on Tuesday, which was not true when the store was a bare
 * object living for the lifetime of the host.
 *
 * `setKeysForSync` exists only on the global state in VS Code and does nothing anywhere without a
 * settings-sync service; it is here because extensions call it unconditionally.
 */
function memento(scope, initial, global) {
  var store = Object.create(null);
  if (initial && typeof initial === 'object') {
    Object.keys(initial).forEach(function (k) { store[k] = initial[k]; });
  }
  var api = {
    get: function (key, fallback) { return store[key] === undefined ? fallback : store[key]; },
    update: function (key, value) {
      if (value === undefined) delete store[key];
      else store[key] = value;
      self.postMessage({ type: 'save-state', scope: scope, state: store });
      return Promise.resolve();
    },
    keys: function () { return Object.keys(store); }
  };
  if (global) api.setKeysForSync = function () {};
  return api;
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
// The Node host resolves `require('vscode')` itself and knows which extension's module asked, so
// it uses this to hand each one its own attributed view of the API.
self.prismVscodeFor = vscodeFor;
self.prismMakeRequire = makeRequire;

self.postMessage({ type: 'host-ready' });
