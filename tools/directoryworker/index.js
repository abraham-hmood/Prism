/**
 * A Prism server directory, as a Cloudflare Worker.
 *
 * ## Why this exists beside the PHP endpoint
 *
 * `tools/directoryserver/` is the same directory as a PHP file, which needs a host that runs PHP
 * and -- as it turned out -- hands a plain HTTP request the PHP rather than a browser challenge
 * page. Free hosts that front every request with a JavaScript check cannot answer a Prism client at
 * all, so this is the version that is always reachable: one small Worker in front of a D1 table.
 *
 * ## The protocol is the same one
 *
 *   GET  /  -> {"servers":[{"name":...,"address":...,"port":...}, ...]}
 *   POST /  <- {"name":...,"address":...,"port":...}   -> {"ok":true,"count":n}
 *
 * Prism's HTTP directory client reads the first and posts the second, so a device cannot tell this
 * apart from the PHP version, and a directory the user hosts either way is the same row on screen.
 *
 * ## What actually keeps it usable
 *
 * The credential is the device's own token and is deliberately NOT checked: every install generates
 * its own, and a public directory every device must be able to write to cannot also demand one
 * secret they all share. What is checked is everything that makes an open list dangerous -- the
 * address must be an IP literal or a plain hostname (never a URL or a path), the port must be in
 * range, names are truncated, one address writes at most a dozen times an hour, rows are capped and
 * anything not refreshed in thirty days is dropped, so a flood of junk expires instead of growing
 * and a server that moved does not stay dialable forever.
 */

const MAX_ROWS = 300;
const MAX_NAME = 80;
const WRITES_PER_HOUR = 12;
const STALE_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_BODY = 8182;

const JSON_HEADERS = {
  'Content-Type': 'application/json; charset=utf-8',
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

let schemaReady = null;

/** Creates the tables once per isolate. A failure is not cached, so the next request retries. */
function ensureSchema(env) {
  if (!schemaReady) {
    schemaReady = env.DB.batch([
      env.DB.prepare(
        'CREATE TABLE IF NOT EXISTS servers (' +
          'address TEXT NOT NULL, port INTEGER NOT NULL, name TEXT NOT NULL, ' +
          'updated_at INTEGER NOT NULL, PRIMARY KEY (address, port))',
      ),
      env.DB.prepare('CREATE TABLE IF NOT EXISTS writers (ip TEXT NOT NULL, at INTEGER NOT NULL)'),
      env.DB.prepare('CREATE INDEX IF NOT EXISTS writers_ip_at ON writers (ip, at)'),
    ]).catch((error) => {
      schemaReady = null;
      throw error;
    });
  }
  return schemaReady;
}

/**
 * A credential, in whichever of the three forms the client used.
 *
 * Prism sends the device token as the password half of HTTP Basic, which is what the PHP endpoint
 * reads too. The value is not compared -- see the file comment -- but its PRESENCE is required, so
 * an unauthenticated crawler and a stray browser are turned away before they write anything.
 */
function presentedCredential(request) {
  const header = request.headers.get('Authorization') || '';
  if (/^Bearer\s+/i.test(header)) return header.slice(7).trim();
  if (/^Basic\s+/i.test(header)) {
    const encoded = header.slice(6).trim();
    try {
      const pair = atob(encoded);
      const colon = pair.indexOf(':');
      return colon < 0 ? '' : pair.slice(colon + 1);
    } catch {
      return '';
    }
  }
  return (request.headers.get('X-Prism-Token') || '').trim();
}

/** An address Prism may be told to dial: an IP literal or a plain hostname. */
/**
 * A range that can never be a real server: RFC 2606/5737 documentation addresses.
 *
 * Listing one is always wrong -- a client that adds it dials nothing -- and it is also how a test
 * row is recognised and dropped instead of staying in the public list until it expires.
 */
const DOCUMENTATION_ADDRESSES = ['192.0.2.%', '198.51.100.%', '203.0.113.%'];

function isDocumentationAddress(address) {
  return DOCUMENTATION_ADDRESSES.some((prefix) => address.startsWith(prefix.slice(0, -1)));
}

/** An address Prism may be told to dial: an IP literal or a plain hostname. */
function validAddress(address) {
  if (typeof address !== 'string' || address.length === 0 || address.length > 253) return false;
  if (/^[0-9.]+$/.test(address)) {
    const parts = address.split('.');
    if (parts.length !== 4) return false;
    return parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255);
  }
  if (address.includes(':')) return /^[0-9a-fA-F:]+$/.test(address);
  // Hostname grammar only. No scheme, no slash, no port, no credentials.
  return /^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$/.test(
    address,
  );
}

async function list(env, now) {
  await env.DB.prepare('DELETE FROM servers WHERE updated_at < ?').bind(now - STALE_MS).run();
  for (const prefix of DOCUMENTATION_ADDRESSES) {
    await env.DB.prepare('DELETE FROM servers WHERE address LIKE ?').bind(prefix).run();
  }
  const { results } = await env.DB.prepare(
    'SELECT name, address, port FROM servers ORDER BY updated_at DESC LIMIT ' + MAX_ROWS,
  ).all();
  return (results || []).map((row) => ({
    name: row.name,
    address: row.address,
    port: row.port,
  }));
}

async function add(env, request, now, ip) {
  const raw = await request.text();
  if (raw.length > MAX_BODY) return json({ error: 'That body is too large.' }, 413);
  let item;
  try {
    item = JSON.parse(raw);
  } catch {
    return json({ error: 'Send a JSON object.' }, 400);
  }
  if (!item || typeof item !== 'object') return json({ error: 'Send a JSON object.' }, 400);

  const address = String(item.address ?? item.host ?? '').trim();
  const port = Number.parseInt(item.port, 10);
  let name = String(item.name ?? '').trim();
  if (name.length > MAX_NAME) name = name.slice(0, MAX_NAME);
  if (!validAddress(address)) {
    return json({ error: 'That address is not a hostname or IP literal.' }, 400);
  }
  if (isDocumentationAddress(address)) {
    return json({ error: 'That address is reserved for documentation, not a server.' }, 400);
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    return json({ error: 'That port is out of range.' }, 400);
  }

  await env.DB.prepare('DELETE FROM writers WHERE at < ?').bind(now - 60 * 60 * 1000).run();
  const recent = await env.DB.prepare('SELECT COUNT(*) AS writes FROM writers WHERE ip = ?')
    .bind(ip)
    .first();
  if ((recent?.writes ?? 0) >= WRITES_PER_HOUR) {
    return json({ error: 'Too many advertisements from this address; try again later.' }, 429);
  }
  await env.DB.prepare('INSERT INTO writers (ip, at) VALUES (?, ?)').bind(ip, now).run();

  await env.DB.prepare(
    'INSERT INTO servers (address, port, name, updated_at) VALUES (?, ?, ?, ?) ' +
      'ON CONFLICT(address, port) DO UPDATE SET name = excluded.name, updated_at = excluded.updated_at',
  )
    .bind(address, port, name || address, now)
    .run();
  // Oldest rows go first, so the cap never turns a busy directory into a rejecting one.
  await env.DB.prepare(
    'DELETE FROM servers WHERE rowid NOT IN ' +
      '(SELECT rowid FROM servers ORDER BY updated_at DESC LIMIT ' + MAX_ROWS + ')',
  ).run();

  const counted = await env.DB.prepare('SELECT COUNT(*) AS rows FROM servers').first();
  return json({ ok: true, count: counted?.rows ?? 0 });
}

export default {
  async fetch(request, env) {
    const now = Date.now();
    try {
      await ensureSchema(env);
      if (!presentedCredential(request)) {
        return json({ error: 'A directory token is required.' }, 401);
      }
      const method = request.method.toUpperCase();
      if (method === 'GET' || method === 'HEAD') {
        return json({ servers: await list(env, now) });
      }
      if (method === 'POST') {
        const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
        return await add(env, request, now, ip);
      }
      return json({ error: 'GET or POST only.' }, 405);
    } catch (error) {
      return json({ error: 'The directory could not be read or written: ' + String(error) }, 500);
    }
  },
};
