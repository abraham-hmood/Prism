# Prism directory server (PHP)

A small endpoint that tells Prism devices which Prism servers exist. It is the HTTP form of the
directory protocol built into Prism: a device adds this URL under **Settings → Network, VPN & Mesh →
Prism server directories**, and every sync reads the list from here.

## Install

1. Copy this folder to a web host with PHP 7.4 or newer.
2. Edit `config.php` and set `DIRECTORY_TOKEN` to a long random value:
   `php -r "echo bin2hex(random_bytes(24));"`
3. Make `data/` writable by the web server. `data/servers.json` is created on the first write.
4. Keep `data/` and `config.php` out of reach of the web root if your host allows it. The included
   `.htaccess` files deny direct access on Apache; on nginx add:

   ```
   location ~ ^/directoryserver/(config\.php|data/) { deny all; }
   ```

## Use it from Prism

Add a directory on the Prism servers screen:

| Field | Value |
| --- | --- |
| URL | `https://example.com/directoryserver/` |
| Username | `prism` |
| Password | the same value as `DIRECTORY_TOKEN` |

Prism sends the pair as HTTP Basic, and this endpoint accepts the token either as that password or
as `Authorization: Bearer <token>` / `X-Prism-Token: <token>`.

A **Server** role publishes itself to the directory (POST). A **Client**, **Bridge** or **Relay**
pulls the list (GET) and adds what it does not already have to its fleet.

## Protocol

```
GET  /            -> {"servers":[{"name":"alpha","address":"10.8.0.4","port":8080}]}
POST /            <- {"name":"alpha","address":"10.8.0.4","port":8080}
                  -> {"ok":true,"count":1}
```

Addresses must be an IP literal or a plain hostname; URLs, paths and ports in the address field are
rejected. The store is capped at 300 rows and every write is done under an exclusive file lock, so
two servers advertising at once cannot lose each other's row.

## Why the token is not optional

A directory decides where a fleet's devices connect. An endpoint anyone can POST to is a way to
point somebody else's clients at your machine. With no token configured, every request is refused.
