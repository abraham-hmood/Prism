<?php
/**
 * A Prism server directory, as a small PHP web endpoint.
 *
 * ## What it does
 *
 * Answers the two questions Prism's ServerDirectory client asks, over HTTP rather than the raw
 * line protocol, so it can be hosted on any ordinary web host with PHP and nothing else:
 *
 *   GET  /            -> {"servers":[{"name":...,"address":...,"port":...}, ...]}
 *   POST /            -> {"name":...,"address":...,"port":...}  adds or refreshes a row
 *
 * ## Why it is locked down this hard
 *
 * A directory tells every device that trusts it where its servers are. An open one is worse than no
 * directory at all: anyone who can POST can point a fleet's clients at their own machine. So every
 * request needs the shared token, the token is compared in constant time, and every advertised
 * address is validated as a hostname or IP literal -- never a URL, never a path.
 *
 * The store is a JSON file written under an exclusive lock, capped at MAX_SERVERS rows, so a flood
 * cannot fill the disk and two concurrent advertisements cannot lose each other's writes.
 */

declare(strict_types=1);

require __DIR__ . '/config.php';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store');

const MAX_SERVERS = 300;

/** Constant-time comparison that does not depend on hash_equals existing. */
function token_matches(string $given, string $expected): bool
{
    if ($expected === '') {
        return false;
    }
    if (function_exists('hash_equals')) {
        return hash_equals($expected, $given);
    }
    if (strlen($given) !== strlen($expected)) {
        return false;
    }
    $diff = 0;
    for ($i = 0; $i < strlen($expected); $i++) {
        $diff |= ord($given[$i]) ^ ord($expected[$i]);
    }
    return $diff === 0;
}

function fail(int $status, string $message): void
{
    http_response_code($status);
    echo json_encode(['error' => $message]);
    exit;
}

/**
 * The token, from whichever header the client used.
 *
 * PRISM SENDS IT AS HTTP BASIC. ServerDirectory's client puts the directory's password in the
 * standard Basic pair, so the password half is the token; reading only Bearer here would have
 * refused every request the app actually makes. The other two forms are for curl and scripts.
 */
function presented_token(): string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (stripos($header, 'Bearer ') === 0) {
        return trim(substr($header, 7));
    }
    if (stripos($header, 'Basic ') === 0) {
        $pair = base64_decode(substr($header, 6), true);
        if (is_string($pair) && strpos($pair, ':') !== false) {
            return substr($pair, strpos($pair, ':') + 1);
        }
        return '';
    }
    return trim($_SERVER['HTTP_X_PRISM_TOKEN'] ?? '');
}

/** An address Prism may be told to dial: an IPv4/IPv6 literal or a plain hostname. */
function valid_address(string $address): bool
{
    if ($address === '' || strlen($address) > 253) {
        return false;
    }
    if (filter_var($address, FILTER_VALIDATE_IP) !== false) {
        return true;
    }
    // Hostname grammar only. No scheme, no slash, no port, no credentials.
    return preg_match('/^[A-Za-z0-9]([A-Za-z0-9\-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9\-]{0,61}[A-Za-z0-9])?)*$/', $address) === 1;
}

/** Reads the store under a shared lock. */
function load_servers(): array
{
    $handle = @fopen(STORE_FILE, 'c+');
    if ($handle === false) {
        return [];
    }
    flock($handle, LOCK_SH);
    $raw = stream_get_contents($handle);
    flock($handle, LOCK_UN);
    fclose($handle);
    $rows = json_decode($raw === false ? '' : $raw, true);
    return is_array($rows) ? $rows : [];
}

/** Replaces the store under an exclusive lock. */
function save_servers(array $rows): bool
{
    $handle = @fopen(STORE_FILE, 'c+');
    if ($handle === false) {
        return false;
    }
    if (!flock($handle, LOCK_EX)) {
        fclose($handle);
        return false;
    }
    ftruncate($handle, 0);
    rewind($handle);
    $json = json_encode(array_values($rows), JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
    $ok = fwrite($handle, $json === false ? '[]' : $json) !== false;
    fflush($handle);
    flock($handle, LOCK_UN);
    fclose($handle);
    return $ok;
}

if (!token_matches(presented_token(), DIRECTORY_TOKEN)) {
    fail(401, 'A directory token is required.');
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    $rows = array_slice(load_servers(), 0, MAX_SERVERS);
    echo json_encode(['servers' => $rows], JSON_UNESCAPED_SLASHES);
    exit;
}

if ($method === 'POST') {
    $body = file_get_contents('php://input', false, null, 0, 8192);
    $item = json_decode($body === false ? '' : $body, true);
    if (!is_array($item)) {
        fail(400, 'Send a JSON object.');
    }

    $address = trim((string)($item['address'] ?? $item['host'] ?? ''));
    $port = (int)($item['port'] ?? 0);
    $name = trim((string)($item['name'] ?? ''));
    if (strlen($name) > 80) {
        $name = substr($name, 0, 80);
    }
    if (!valid_address($address)) {
        fail(400, 'That address is not a hostname or IP literal.');
    }
    if ($port < 1 || $port > 65535) {
        fail(400, 'That port is out of range.');
    }

    $rows = load_servers();
    $now = time();
    $updated = false;
    foreach ($rows as $index => $row) {
        if (($row['address'] ?? '') === $address && (int)($row['port'] ?? 0) === $port) {
            $rows[$index]['name'] = $name !== '' ? $name : $address;
            $rows[$index]['updated_at'] = $now;
            $updated = true;
            break;
        }
    }
    if (!$updated) {
        if (count($rows) >= MAX_SERVERS) {
            fail(507, 'The directory is full.');
        }
        $rows[] = [
            'name' => $name !== '' ? $name : $address,
            'address' => $address,
            'port' => $port,
            'updated_at' => $now,
        ];
    }
    if (!save_servers($rows)) {
        fail(500, 'The directory could not be written.');
    }
    echo json_encode(['ok' => true, 'count' => count($rows)]);
    exit;
}

fail(405, 'GET or POST only.');
