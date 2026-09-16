/*
 * Prism Editor -- the browser half.
 *
 * WHY MONACO RATHER THAN A HAND-WRITTEN TEXT VIEW. Monaco is the editor component out of VS Code,
 * unmodified. Taking it means multi-cursor, column selection, regex find/replace, bracket matching,
 * folding, 80-odd syntax grammars and VS Code's exact keybindings all behave the way people already
 * expect, and the Selection / Go / Edit menus become thin wrappers over commands that already exist
 * rather than sixty separate reimplementations. Writing an editor of that quality from scratch is
 * years of work, and it would still not be the one anyone asked for.
 *
 * THE NATIVE SIDE OWNS THE FILES. Everything here operates on in-memory models; opening, saving and
 * listing go through the `Prism` bridge to Kotlin, which owns storage permissions and the document
 * pickers. This file never touches a path directly.
 */
(function () {
  'use strict';

  var editor = null;
  var monacoRef = null;

  /** path -> { model, viewState, dirty }. The open editors. */
  var openFiles = Object.create(null);
  var activePath = null;

  /** Resolvers for round-trips into Kotlin, keyed by request id. */
  var pending = Object.create(null);
  var nextRequestId = 1;

  // ---------------------------------------------------------------- bridge

  function android() {
    return typeof Prism !== 'undefined' ? Prism : null;
  }

  /** Calls Kotlin and resolves with its reply. Kotlin answers via `PrismEditor.resolve`. */
  function ask(method, payload) {
    return new Promise(function (resolve) {
      var bridge = android();
      if (!bridge || typeof bridge.request !== 'function') { resolve(null); return; }
      var id = nextRequestId++;
      pending[id] = resolve;
      try {
        bridge.request(id, method, JSON.stringify(payload || {}));
      } catch (e) {
        delete pending[id];
        resolve(null);
      }
    });
  }

  function notify(event, payload) {
    var bridge = android();
    if (bridge && typeof bridge.notify === 'function') {
      try { bridge.notify(event, JSON.stringify(payload || {})); } catch (e) { /* ignore */ }
    }
  }

  // ---------------------------------------------------------------- languages

  var EXTENSION_LANGUAGE = {
    java: 'java', kt: 'kotlin', kts: 'kotlin', py: 'python', pyw: 'python',
    js: 'javascript', mjs: 'javascript', cjs: 'javascript', ts: 'typescript', tsx: 'typescript',
    jsx: 'javascript', json: 'json', html: 'html', htm: 'html', css: 'css', scss: 'scss',
    xml: 'xml', md: 'markdown', markdown: 'markdown', sh: 'shell', bash: 'shell', zsh: 'shell',
    yml: 'yaml', yaml: 'yaml', toml: 'ini', ini: 'ini', properties: 'ini', gradle: 'groovy',
    c: 'c', h: 'c', cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp', cs: 'csharp', go: 'go',
    rs: 'rust', rb: 'ruby', php: 'php', swift: 'swift', sql: 'sql', dart: 'dart', lua: 'lua',
    txt: 'plaintext', log: 'plaintext'
  };

  function languageFor(path) {
    var name = String(path || '').split('/').pop();
    var dot = name.lastIndexOf('.');
    if (dot < 0) return 'plaintext';
    return EXTENSION_LANGUAGE[name.slice(dot + 1).toLowerCase()] || 'plaintext';
  }

  // ---------------------------------------------------------------- boot

  require.config({ paths: { vs: '/monaco/vs' } });

  require(['vs/editor/editor.main'], function () {
    monacoRef = window.monaco;

    var boot = document.getElementById('boot');
    if (boot) boot.parentNode.removeChild(boot);

    editor = monacoRef.editor.create(document.getElementById('root'), {
      value: '',
      language: 'plaintext',
      theme: 'prism-ios-dark',   // replaced by Kotlin once it reports the device appearance
      automaticLayout: true,
      fontSize: 14,
      lineHeight: 22,
      letterSpacing: 0.2,
      padding: { top: 12, bottom: 12 },
      roundedSelection: true,
      cursorBlinking: 'smooth',
      cursorSmoothCaretAnimation: 'on',
      minimap: { enabled: false },   // a phone screen has no room for one
      scrollBeyondLastLine: false,
      wordWrap: 'off',
      renderWhitespace: 'selection',
      tabSize: 4,
      // Touch devices have no hover, so the gutter controls have to be permanently visible or they
      // are unreachable.
      folding: true,
      showFoldingControls: 'always',
      lineNumbersMinChars: 3,
      scrollbar: { verticalScrollbarSize: 14, horizontalScrollbarSize: 14 }
    });

    defineThemes();
    registerCompletionProviders();
    wireEvents();

    notify('ready', { version: monacoRef.editor.EditorOptions ? 'monaco' : 'monaco' });
  });


  /**
   * iOS-styled themes for the code surface.
   *
   * Defined here rather than taking Monaco's `vs`/`vs-dark` so the editor matches the rest of
   * Prism: the same grouped background, the same label greys, and the system accent for keywords.
   * Two of them, because the page follows the device's appearance and a dark editor on a light
   * phone reads as a different app embedded in this one.
   */
  function defineThemes() {
    monacoRef.editor.defineTheme('prism-ios-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: '', foreground: 'FFFFFF', background: '1C1C1E' },
        { token: 'comment', foreground: '8E8E93', fontStyle: 'italic' },
        { token: 'keyword', foreground: '0A84FF' },
        { token: 'string', foreground: 'FF9F0A' },
        { token: 'number', foreground: '30D158' },
        { token: 'type', foreground: '64D2FF' },
        { token: 'function', foreground: 'BF5AF2' },
        { token: 'variable', foreground: 'FFFFFF' }
      ],
      colors: {
        'editor.background': '#1C1C1E',
        'editor.foreground': '#FFFFFF',
        'editorLineNumber.foreground': '#48484A',
        'editorLineNumber.activeForeground': '#0A84FF',
        'editor.selectionBackground': '#0A84FF55',
        'editor.lineHighlightBackground': '#2C2C2E',
        'editorCursor.foreground': '#0A84FF',
        'editorWidget.background': '#2C2C2E',
        'editorSuggestWidget.background': '#2C2C2E',
        'editorSuggestWidget.selectedBackground': '#0A84FF33',
        'scrollbarSlider.background': '#48484A80'
      }
    });

    monacoRef.editor.defineTheme('prism-ios-light', {
      base: 'vs',
      inherit: true,
      rules: [
        { token: '', foreground: '000000', background: 'FFFFFF' },
        { token: 'comment', foreground: '8E8E93', fontStyle: 'italic' },
        { token: 'keyword', foreground: '007AFF' },
        { token: 'string', foreground: 'C93400' },
        { token: 'number', foreground: '248A3D' },
        { token: 'type', foreground: '0071A4' },
        { token: 'function', foreground: '8944AB' },
        { token: 'variable', foreground: '000000' }
      ],
      colors: {
        'editor.background': '#FFFFFF',
        'editor.foreground': '#000000',
        'editorLineNumber.foreground': '#C7C7CC',
        'editorLineNumber.activeForeground': '#007AFF',
        'editor.selectionBackground': '#007AFF33',
        'editor.lineHighlightBackground': '#F2F2F7',
        'editorCursor.foreground': '#007AFF',
        'editorWidget.background': '#F2F2F7',
        'editorSuggestWidget.background': '#FFFFFF',
        'editorSuggestWidget.selectedBackground': '#007AFF22',
        'scrollbarSlider.background': '#C7C7CC80'
      }
    });
  }

  function wireEvents() {
    editor.onDidChangeModelContent(function () {
      if (!activePath) return;
      var entry = openFiles[activePath];
      if (entry && !entry.dirty) {
        entry.dirty = true;
        notify('dirty', { path: activePath, dirty: true });
      }
      notify('changed', { path: activePath });
    });

    editor.onDidChangeCursorPosition(function (e) {
      notify('cursor', { line: e.position.lineNumber, column: e.position.column });
    });

    // The soft keyboard.
    //
    // Monaco puts the caret in a hidden <textarea> and expects the platform to raise an IME for it.
    // Android will not do that on its own here: the WebView has to hold focus and be asked. So the
    // page reports the textarea's focus, and Kotlin calls showSoftInput -- at a point where there is
    // certainly an editable element for the input connection to bind to, which is what makes it work
    // where asking on touch does not.
    editor.onDidFocusEditorText(function () { notify('focus', {}); });
    editor.onDidBlurEditorText(function () { notify('blur', {}); });
  }

  // ---------------------------------------------------------------- completion
  //
  // Java, Kotlin and Python completion is answered by Kotlin, which holds the symbol index for the
  // open folder and the bundled standard-library tables. Doing it here would mean shipping those
  // tables into the WebView and re-scanning every file in JavaScript; the native side has already
  // read them.
  //
  // Every other language falls through to whatever Monaco provides on its own (TypeScript, JSON,
  // HTML and CSS have real language services built in).

  function registerCompletionProviders() {
    ['java', 'kotlin', 'python'].forEach(function (language) {
      monacoRef.languages.registerCompletionItemProvider(language, {
        triggerCharacters: ['.', ':'],
        provideCompletionItems: function (model, position) {
          var word = model.getWordUntilPosition(position);
          var range = {
            startLineNumber: position.lineNumber,
            endLineNumber: position.lineNumber,
            startColumn: word.startColumn,
            endColumn: word.endColumn
          };

          var lineToCursor = model.getValueInRange({
            startLineNumber: position.lineNumber,
            startColumn: 1,
            endLineNumber: position.lineNumber,
            endColumn: position.column
          });

          return Promise.all([
            ask('complete', {
              language: language,
              path: activePath || '',
              prefix: word.word,
              line: lineToCursor,
              lineNumber: position.lineNumber,
              text: model.getValue()
            }),
            hostCompletions(language, activePath || '', model.getValue(),
                            position.lineNumber, position.column)
          ]).then(function (both) {
            var reply = both[0];
            // Extension suggestions come first: an extension was installed on purpose, and its
            // language knowledge is more specific than the built-in symbol index.
            var items = (both[1] || []).concat((reply && reply.items) || []);
            return {
              suggestions: items.map(function (item) {
                return {
                  label: item.label,
                  kind: kindOf(item.kind),
                  insertText: item.insert || item.label,
                  detail: item.detail || '',
                  documentation: item.doc || '',
                  range: range,
                  sortText: item.sort || item.label
                };
              })
            };
          });
        }
      });
    });
  }

  function kindOf(name) {
    var k = monacoRef.languages.CompletionItemKind;
    switch (name) {
      case 'keyword': return k.Keyword;
      case 'class': return k.Class;
      case 'interface': return k.Interface;
      case 'method': return k.Method;
      case 'function': return k.Function;
      case 'field': return k.Field;
      case 'property': return k.Property;
      case 'variable': return k.Variable;
      case 'module': return k.Module;
      case 'snippet': return k.Snippet;
      default: return k.Text;
    }
  }


  // ---------------------------------------------------------------- extension hosts
  //
  // There are two, and an extension goes to whichever one it was built for.
  //
  // THE WORKER HOST runs web extensions -- the ones declaring `browser` in their manifest. It is
  // VS Code's own web-extension model: no Node, no filesystem, and no way for a misbehaving
  // extension to freeze the editor, which on a phone is indistinguishable from the app hanging.
  //
  // THE NODE HOST runs everything else. Most of the marketplace declares `main`, calls
  // `require('fs')` or `child_process`, and cannot load in a Worker at any price. Those go to a
  // real Node.js runtime living in Prism's own process (libnode.so), which Kotlin owns; this page
  // talks to it through the bridge. The protocol is deliberately identical to the Worker's, so
  // everything below handles both without caring which is which.

  var workerHost = null;
  var nodeHost = null;
  var hostCompletionId = 1;
  var hostCompletionWaiters = Object.create(null);
  var registeredCommands = [];

  /** Every host that is up, for broadcasts (completions, events). */
  function liveHosts() {
    var list = [];
    if (workerHost && workerHost.ready) list.push(workerHost);
    if (nodeHost && nodeHost.ready) list.push(nodeHost);
    return list;
  }

  function startHost() {
    if (workerHost) return;
    var worker;
    try {
      worker = new Worker('extension-host.js');
    } catch (e) {
      notify('host-error', { message: 'Could not start the extension host: ' + e.message });
      return;
    }
    workerHost = {
      kind: 'worker',
      ready: false,
      post: function (msg) { worker.postMessage(msg); }
    };
    worker.onmessage = function (event) { onHostMessage(workerHost, event.data || {}); };
  }

  /**
   * Brings up the Node host.
   *
   * Kotlin does the actual work -- starting the runtime, handing it the socket -- because the
   * runtime is a native library, not something a page can launch. This only opens the channel and
   * reports whether there is one.
   */
  function startNodeHost() {
    if (nodeHost) return nodeHost.ready;
    var bridge = android();
    if (!bridge || typeof bridge.nodeSend !== 'function') return false;
    nodeHost = {
      kind: 'node',
      ready: false,
      post: function (msg) {
        try { bridge.nodeSend(JSON.stringify(msg)); } catch (e) { /* host gone */ }
      }
    };
    notify('node-host-wanted', {});
    return true;
  }

  function onHostMessage(hostRef, msg) {
    switch (msg.type) {
      case 'host-ready':
        hostRef.ready = true;
        notify('host-ready', { kind: hostRef.kind });
        break;

      case 'activated':
        notify('extension-activated', { id: msg.id });
        break;

      case 'activation-failed':
        notify('extension-failed', { id: msg.id, message: msg.message });
        break;

      case 'command-registered':
        registeredCommands.push(msg.id);
        break;

      case 'completions':
        var waiter = hostCompletionWaiters[msg.id];
        if (waiter) { delete hostCompletionWaiters[msg.id]; waiter(msg.items || []); }
        break;

      case 'call':
        handleHostCall(hostRef, msg);
        break;

      case 'error':
        notify('host-error', { message: msg.message });
        break;
    }
  }

  /** Requests an extension asks of the editor. Answered here, or forwarded to Kotlin. */
  function handleHostCall(hostRef, msg) {
    var reply = function (value) {
      hostRef.post({ type: 'call-result', id: msg.id, value: value === undefined ? null : value });
    };

    switch (msg.method) {
      case 'executeCommand':
        reply(window.PrismEditor.runCommand(msg.payload && msg.payload.id));
        break;
      case 'message':
        notify('extension-message', msg.payload || {});
        reply(null);
        break;
      case 'status':
      case 'output':
        notify('extension-output', msg.payload || {});
        reply(null);
        break;
      case 'clipboardRead':
        reply('');
        break;
      default:
        // Everything else needs the device, so Kotlin answers it.
        ask('host:' + msg.method, msg.payload).then(reply);
        break;
    }
  }

  /** Asks every loaded extension, in every host, for completions -- with a deadline. */
  function hostCompletions(language, path, text, line, column) {
    var hosts = liveHosts();
    if (!hosts.length) return Promise.resolve([]);
    return Promise.all(hosts.map(function (hostRef) {
      return new Promise(function (resolve) {
        var id = hostCompletionId++;
        hostCompletionWaiters[id] = resolve;
        hostRef.post({
          type: 'complete', id: id, language: language, path: path, text: text,
          line: line, column: column
        });
        // An extension that never answers must not stall the completion list forever.
        setTimeout(function () {
          if (hostCompletionWaiters[id]) { delete hostCompletionWaiters[id]; resolve([]); }
        }, 1200);
      });
    })).then(function (lists) {
      return lists.reduce(function (all, items) { return all.concat(items || []); }, []);
    });
  }

  // ---------------------------------------------------------------- public API
  //
  // Everything Kotlin calls. Kept on one object so the bridge has a single, obvious surface.

  window.PrismEditor = {

    /** Starts the worker that runs web extensions. */
    startExtensionHost: function () { startHost(); },

    /**
     * Loads one installed extension.
     *
     * `runtime` decides the host: 'node' for an extension with a `main` entry point, anything else
     * for a web extension. A Node extension is not handed its source here -- Node reads its own
     * files, which is the point of using Node -- so only the entry path travels.
     */
    activateExtension: function (id, path, source, runtime) {
      if (runtime === 'node') {
        if (!startNodeHost()) return false;
        nodeHost.post({ type: 'activate', id: id, path: path, entry: source });
        return true;
      }
      startHost();
      if (!workerHost) return false;
      workerHost.post({ type: 'activate', id: id, path: path, source: source });
      return true;
    },

    /** A line of JSON from the Node host, handed over by Kotlin. */
    nodeMessage: function (json) {
      if (!nodeHost) startNodeHost();
      if (!nodeHost) return;
      var msg = null;
      try { msg = JSON.parse(json); } catch (e) { return; }
      onHostMessage(nodeHost, msg || {});
    },

    /** Kotlin reporting that the Node runtime went away. */
    nodeHostGone: function (reason) {
      if (nodeHost) { nodeHost.ready = false; nodeHost = null; }
      if (reason) notify('host-error', { message: String(reason) });
    },

    /** Commands contributed by extensions, for the palette. */
    extensionCommands: function () { return JSON.stringify(registeredCommands); },

    runExtensionCommand: function (id) {
      var hosts = liveHosts();
      if (!hosts.length) return false;
      // Broadcast: only the host that registered the id has a handler, and the others answer
      // 'command-missing', which is ignored. Tracking ownership would be a second thing to keep in
      // step for no benefit.
      hosts.forEach(function (hostRef) {
        hostRef.post({ type: 'run-command', id: id, args: [] });
      });
      return true;
    },

    /** Kotlin's reply to `ask`. */
    resolve: function (id, json) {
      var resolver = pending[id];
      if (!resolver) return;
      delete pending[id];
      var value = null;
      try { value = json ? JSON.parse(json) : null; } catch (e) { value = null; }
      resolver(value);
    },

    /** Opens (or focuses) a file. Content comes from Kotlin, which read it off disk. */
    openFile: function (path, content) {
      if (activePath && openFiles[activePath]) {
        openFiles[activePath].viewState = editor.saveViewState();
      }

      var entry = openFiles[path];
      if (!entry) {
        var uri = monacoRef.Uri.parse('prism:///' + encodeURI(path.replace(/^\/+/, '')));
        var existing = monacoRef.editor.getModel(uri);
        var model = existing || monacoRef.editor.createModel(content || '', languageFor(path), uri);
        if (existing) existing.setValue(content || '');
        entry = { model: model, viewState: null, dirty: false };
        openFiles[path] = entry;
      } else {
        entry.model.setValue(content || '');
        entry.dirty = false;
      }

      activePath = path;
      editor.setModel(entry.model);
      if (entry.viewState) editor.restoreViewState(entry.viewState);
      editor.focus();
      notify('opened', { path: path, language: entry.model.getLanguageId() });
    },

    /** A brand-new unsaved buffer. */
    newFile: function (path, language, content) {
      var uri = monacoRef.Uri.parse('prism:///' + encodeURI(String(path).replace(/^\/+/, '')));
      var model = monacoRef.editor.getModel(uri) ||
        monacoRef.editor.createModel(content || '', language || 'plaintext', uri);
      model.setValue(content || '');
      openFiles[path] = { model: model, viewState: null, dirty: true };
      activePath = path;
      editor.setModel(model);
      editor.focus();
      notify('opened', { path: path, language: model.getLanguageId() });
    },

    closeFile: function (path) {
      var entry = openFiles[path];
      if (!entry) return;
      entry.model.dispose();
      delete openFiles[path];
      if (activePath === path) {
        activePath = null;
        var remaining = Object.keys(openFiles);
        if (remaining.length) {
          window.PrismEditor.focusFile(remaining[remaining.length - 1]);
        } else {
          editor.setModel(null);
        }
      }
    },

    focusFile: function (path) {
      var entry = openFiles[path];
      if (!entry) return;
      if (activePath && openFiles[activePath]) {
        openFiles[activePath].viewState = editor.saveViewState();
      }
      activePath = path;
      editor.setModel(entry.model);
      if (entry.viewState) editor.restoreViewState(entry.viewState);
      editor.focus();
      notify('opened', { path: path, language: entry.model.getLanguageId() });
    },

    /** Current buffer text, for Kotlin to write to disk. */
    contentOf: function (path) {
      var entry = openFiles[path || activePath];
      return entry ? entry.model.getValue() : '';
    },

    markSaved: function (path) {
      var entry = openFiles[path];
      if (entry) {
        entry.dirty = false;
        notify('dirty', { path: path, dirty: false });
      }
    },

    activeFile: function () { return activePath || ''; },

    isDirty: function (path) {
      var entry = openFiles[path || activePath];
      return !!(entry && entry.dirty);
    },

    dirtyFiles: function () {
      return JSON.stringify(Object.keys(openFiles).filter(function (p) { return openFiles[p].dirty; }));
    },

    /**
     * Runs one of Monaco's own commands.
     *
     * This is what makes the Selection, Go and Edit menus real rather than decorative: every item in
     * them names a command Monaco already implements, so "Add Cursor Below" is the same code path
     * the keyboard shortcut uses, not a second implementation that drifts from it.
     */
    runCommand: function (id) {
      if (!editor) return false;
      try {
        editor.focus();
        editor.trigger('prism-menu', id, null);
        return true;
      } catch (e) {
        return false;
      }
    },

    /** Editor-wide options the View menu toggles. */
    setOption: function (name, value) {
      if (!editor) return;
      var options = {};
      options[name] = value;
      editor.updateOptions(options);
    },

    getOption: function (name) {
      if (!editor) return null;
      try { return editor.getRawOptions()[name]; } catch (e) { return null; }
    },

    setTheme: function (theme) { monacoRef.editor.setTheme(theme); },

    setFontSize: function (size) { editor.updateOptions({ fontSize: Math.max(8, Math.min(32, size)) }); },

    /** Go to Line/Column. */
    revealPosition: function (line, column) {
      if (!editor) return;
      var position = { lineNumber: Math.max(1, line), column: Math.max(1, column || 1) };
      editor.setPosition(position);
      editor.revealPositionInCenter(position);
      editor.focus();
    },

    /** Find/replace driven from the native dialogs rather than Monaco's own widget. */
    find: function (query, useRegex, matchCase, wholeWord, replaceWith, replaceAll) {
      var model = editor && editor.getModel();
      if (!model || !query) return JSON.stringify({ matches: 0 });

      var matches = model.findMatches(query, true, !!useRegex, !!matchCase, wholeWord ? ' \t\n' : null, true);
      if (replaceWith === null || replaceWith === undefined) {
        if (matches.length) {
          editor.setSelection(matches[0].range);
          editor.revealRangeInCenter(matches[0].range);
        }
        return JSON.stringify({ matches: matches.length });
      }

      var edits = (replaceAll ? matches : matches.slice(0, 1)).map(function (m) {
        return { range: m.range, text: replaceWith };
      });
      if (edits.length) editor.executeEdits('prism-replace', edits);
      return JSON.stringify({ matches: edits.length });
    },

    /** Symbols in the current file, for Go to Symbol. */
    symbols: function () {
      var model = editor && editor.getModel();
      if (!model) return '[]';
      var text = model.getValue();
      var out = [];
      var patterns = [
        { re: /^\s*(?:public|private|protected|internal|open|final|abstract|sealed|data)?\s*(?:class|interface|object|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)/gm, kind: 'class' },
        { re: /^\s*(?:public|private|protected|internal|open|override|suspend|static|final)?[\w<>\[\]\s,?]*\s(fun|def)\s+([A-Za-z_][A-Za-z0-9_]*)/gm, kind: 'function' },
        { re: /^\s*(?:public|private|protected|static|final)[\w<>\[\],\s]*\s([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*\{/gm, kind: 'method' }
      ];
      patterns.forEach(function (p) {
        var m;
        while ((m = p.re.exec(text)) !== null) {
          var name = m[2] || m[1];
          if (!name) continue;
          out.push({ name: name, kind: p.kind, line: model.getPositionAt(m.index).lineNumber });
        }
      });
      out.sort(function (a, b) { return a.line - b.line; });
      return JSON.stringify(out);
    },

    /** Word under the cursor -- what Go to Definition searches for. */
    wordAtCursor: function () {
      var model = editor && editor.getModel();
      if (!model) return '';
      var position = editor.getPosition();
      if (!position) return '';
      var word = model.getWordAtPosition(position);
      return word ? word.word : '';
    },

    selectedText: function () {
      var model = editor && editor.getModel();
      var selection = editor && editor.getSelection();
      if (!model || !selection) return '';
      return model.getValueInRange(selection);
    },

    insertText: function (text) {
      if (!editor) return;
      var selection = editor.getSelection();
      editor.executeEdits('prism-insert', [{ range: selection, text: text, forceMoveMarkers: true }]);
      editor.focus();
    },

    /** Problems, for the Problems view and F8 navigation. */
    markers: function () {
      if (!monacoRef || !editor || !editor.getModel()) return '[]';
      var found = monacoRef.editor.getModelMarkers({ resource: editor.getModel().uri });
      return JSON.stringify(found.map(function (m) {
        return {
          message: m.message, severity: m.severity, line: m.startLineNumber, column: m.startColumn
        };
      }));
    }
  };
})();
