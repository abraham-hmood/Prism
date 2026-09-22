# Editor extension-host test

Drives `node-extension-host.js` exactly the way Kotlin does — loopback socket, token handshake,
one `activate` message — and checks what the extension on the other end actually received.

It runs on any machine with Node. That is the point: the extension host is the one piece of the
editor whose behaviour does not depend on Android at all, so it can be tested in a second on a
desktop instead of in a minute on a phone.

    cd tools/editor-host-test
    node harness.js

It copies nothing: `harness.js` reads the real host scripts out of `app/src/main/assets/editor/`,
so a change that breaks the protocol fails here immediately.

## What it caught

The token race. `net.connect` returns a socket object before it has connected, so the host's first
`send` — `host-ready`, posted while the shared shim was still evaluating — could reach the stream
ahead of the authentication token. Kotlin read it as the token, decided the connection was an
impostor and closed it. Whether it happened depended on how long the shim took to evaluate, so it
looked like "extensions sometimes do not load".
