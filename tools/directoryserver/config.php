<?php
/**
 * Configuration for the Prism directory endpoint.
 *
 * Copy this file, set a long random token, and point Prism at the URL with that token in its
 * "password" field for the directory (the client sends it as HTTP Basic). Keep this file out of
 * version control once it holds a real token.
 */

declare(strict_types=1);

/**
 * The shared secret every request must present.
 *
 * Generate one with: php -r "echo bin2hex(random_bytes(24));"
 * An empty token makes the endpoint refuse everything, which is deliberate: a directory that
 * silently ran open would advertise a fleet to whoever asked first.
 */
const DIRECTORY_TOKEN = 'change-me-to-a-long-random-token';

/** Where the advertised servers are kept. Must be writable by the web server, and not web-readable. */
const STORE_FILE = __DIR__ . '/data/servers.json';
