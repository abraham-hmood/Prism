/*
 * The Node extension host.
 *
 * This is the script the in-process Node.js runtime boots. It runs VS Code extensions that declare
 * `main` -- which is most of the marketplace -- in a real Node, with `fs`, `child_process`, native
 * addons and their own `node_modules`, none of which a Web Worker can offer.
 *
 * ## It does not reimplement the `vscode` API
 *
 * It loads `extension-host.js`, the same file the Worker host runs, inside a synthetic `self`. That
 * file owns the API shim and the message protocol; this file owns the two things that differ in
 * Node -- how modules are loaded, and how messages get to Kotlin. One shim, two hosts, no drift
 * between them.
 *
 * ## The channel
 *
 * Newline-delimited JSON over a loopback socket to Kotlin, which forwards each line to the editor
 * page and back. A socket rather than stdout because stdout in an Android app is logcat: shared,
 * lossy, and read by everything. The first line sent is a token Kotlin generated, because a
 * loopback listener is reachable by any app on the device and an unauthenticated one would be an
 * open door into the editor's filesystem access.
 *
 * ## What still does not work here
 *
 * Android refuses to execute binaries stored in an app's data directory, so an extension that
 * ships its own executable and spawns it still fails -- `child_process` works for anything already
 * executable on the device, and not for a bundled CLI. That is a kernel rule, not a gap in this
 * file.
 */
'use strict';

const fs = require('fs');
const net = require('net');
const path = require('path');
const Module = require('module');

const PORT = parseInt(process.env.PRISM_EDITOR_PORT || '0', 10);
const TOKEN = process.env.PRISM_EDITOR_TOKEN || '';
const ASSET_DIR = process.env.PRISM_EDITOR_ASSETS || __dirname;

// ---------------------------------------------------------------- the shim

/**
 * The `self` the shared host source expects.
 *
 * It is a plain object, not `globalThis`: the shim assigns `self.onmessage` and reads hooks off
 * `self`, and keeping that on its own object means nothing it does can collide with Node's globals
 * or with an extension's.
 */
const self = {
  postMessage: function (msg) { send(msg); },
  onmessage: null,
};

const requires = Object.create(null);   // extension id -> require rooted at its directory
const roots = Object.create(null);      // extension id -> extension directory

/**
 * `require` as an extension sees it, minus `vscode` which the shim answers first.
 *
 * Rooted at the extension's own directory so its bundled dependencies resolve exactly as they would
 * on desktop. An unknown module throws Node's own MODULE_NOT_FOUND, which is what the extension's
 * error handling was written against.
 */
self.prismRequire = function (id, name) {
  const req = requires[id];
  if (!req) throw new Error('Extension "' + id + '" is not loaded');
  return req(name);
};

/** Loads an extension's entry point through Node's module system and returns its exports. */
self.prismLoadModule = function (id, entry) {
  const req = requires[id];
  if (!req) throw new Error('Extension "' + id + '" is not loaded');
  return req(entry);
};

// `require('vscode')` has no package to resolve to -- the module only exists inside a host. VS Code
// itself answers it from the loader; here the same interception happens at Module._load, which
// covers nested requires inside an extension's dependencies as well as its own entry file.
const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  if (request === 'vscode') {
    if (!self.prismVscode) throw new Error('The vscode API is not ready yet');
    return self.prismVscode;
  }
  return originalLoad.apply(this, arguments);
};

// ---------------------------------------------------------------- channel

let socket = null;
const outbox = [];

function send(msg) {
  let line;
  try {
    line = JSON.stringify(msg);
  } catch (e) {
    // An extension can hand back something with a cycle in it. Losing the payload is better than
    // losing the message, so the type survives and the value does not.
    line = JSON.stringify({ type: msg && msg.type, id: msg && msg.id, value: null });
  }
  if (socket && !socket.destroyed) socket.write(line + '\n');
  else outbox.push(line);
}

function deliver(line) {
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }

  // The page sends a Node activation as { entry: <path> }; the shared shim expects `source`. The
  // difference is real and not worth hiding: the Worker is handed the extension's code because it
  // has no filesystem, and Node is handed a path because it does.
  if (msg.type === 'activate' && msg.entry) {
    registerExtension(msg.id, msg.path, msg.entry);
    msg.source = msg.entry;
  }

  if (typeof self.onmessage === 'function') {
    try { self.onmessage({ data: msg }); } catch (e) { report(e); }
  }
}

function registerExtension(id, root, entry) {
  const dir = root || path.dirname(entry);
  roots[id] = dir;
  // createRequire needs a file, not a directory, to anchor resolution; package.json is the one file
  // every extension has.
  requires[id] = Module.createRequire(path.join(dir, 'package.json'));
}

function report(e) {
  send({ type: 'error', message: (e && e.message) || String(e), stack: (e && e.stack) || '' });
}

// ---------------------------------------------------------------- boot

function loadSharedHost() {
  const source = fs.readFileSync(path.join(ASSET_DIR, 'extension-host.js'), 'utf8');
  // Evaluated with `self` in scope rather than required: the file is written for a Worker global,
  // and giving it one is cheaper and less fragile than maintaining a second copy that exports.
  const factory = new Function('self', 'globalThis', source);
  factory(self, self);
}

function connect() {
  socket = net.connect({ host: '127.0.0.1', port: PORT }, function () {
    socket.write(TOKEN + '\n');
    while (outbox.length) socket.write(outbox.shift() + '\n');
  });

  let buffer = '';
  socket.setEncoding('utf8');
  socket.on('data', function (chunk) {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line) deliver(line);
    }
  });

  // When the editor page goes away Kotlin closes the socket, and there is nothing left for this
  // runtime to do. Exiting releases the V8 heap, which on a phone is the whole point.
  socket.on('close', function () { process.exit(0); });
  socket.on('error', function () { process.exit(1); });
}

// An extension throwing on a timer would otherwise take the runtime -- and with it every other
// extension -- down. Report and carry on; that is what VS Code does.
process.on('uncaughtException', report);
process.on('unhandledRejection', function (reason) { report(reason instanceof Error ? reason : new Error(String(reason))); });

if (!PORT || !TOKEN) {
  console.error('prism: no channel configured (PRISM_EDITOR_PORT / PRISM_EDITOR_TOKEN)');
  process.exit(2);
}

connect();
loadSharedHost();
