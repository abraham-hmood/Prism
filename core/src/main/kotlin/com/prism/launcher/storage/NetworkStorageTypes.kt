package com.prism.launcher.storage

import com.prism.launcher.PrismSettings
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * What each kind of networked storage needs before it can be saved.
 *
 * ## Why the catalogue lives in :core
 *
 * The Android Files page, the desktop Files page and anything else that grows later must offer the
 * same list with the same defaults, or a connection created on one is nonsense on the other. The
 * catalog is also the validation: which fields are required, what port is normal, and whether the
 * transport even uses a username are properties of the protocol, not of a dialog.
 *
 * ## Nothing here pretends a transport exists
 *
 * [NetworkStorageType.note] states what the protocol needs beyond what Prism ships, and the browsing
 * code in [com.prism.launcher.NetworkStorage] reports `Unsupported` for the ones the JDK cannot
 * speak. A saved connection is a saved address, not a promise that the bytes will flow; saying so
 * on the sheet is better than a folder that looks empty because nothing was ever fetched.
 */
enum class NetworkFieldKind { TEXT, NUMBER, SECRET, TOGGLE }

/** One extra input a protocol needs, beyond name/host/port/credentials. */
data class NetworkField(
    val key: String,
    val label: String,
    val hint: String = "",
    val kind: NetworkFieldKind = NetworkFieldKind.TEXT,
    val default: String = "",
    val required: Boolean = false,
)

/** A connection type: what the picker shows and what the form becomes when it is chosen. */
data class NetworkStorageType(
    val id: String,
    val title: String,
    val summary: String,
    val defaultPort: Int,
    val hostLabel: String = "Host",
    val hostHint: String = "Host name or IP address",
    val usesCredentials: Boolean = true,
    val fields: List<NetworkField> = emptyList(),
    /** What this transport needs that Prism does not ship, when that is the case. */
    val note: String = "",
)

object NetworkStorageTypes {

    private fun path(default: String = "") = NetworkField(
        key = "path",
        label = "Remote path",
        hint = "Folder to open first",
        default = default,
    )

    private fun passive() = NetworkField(
        key = "passive",
        label = "Passive mode",
        hint = "Recommended; active mode is blocked by most routers",
        kind = NetworkFieldKind.TOGGLE,
        default = "true",
    )

    /**
     * In picker order: the encrypted general-purpose transports first, then the plain ones, then
     * the block and distributed protocols that need a mount service.
     */
    val all: List<NetworkStorageType> = listOf(
        NetworkStorageType(
            id = "sftp",
            title = "SFTP",
            summary = "SSH file transfer, encrypted end to end.",
            defaultPort = 22,
            hostHint = "Host name, IP address or SSH alias",
            fields = listOf(
                path(),
                NetworkField("keyFile", "Private key file", "Optional; empty uses the password", kind = NetworkFieldKind.TEXT),
            ),
        ),
        NetworkStorageType(
            id = "ftps",
            title = "FTPS",
            summary = "FTP wrapped in TLS from the first byte (implicit).",
            defaultPort = 990,
            fields = listOf(path(), passive()),
        ),
        NetworkStorageType(
            id = "ftpes",
            title = "FTPES",
            summary = "FTP that upgrades to TLS with AUTH TLS (explicit).",
            defaultPort = 21,
            fields = listOf(path(), passive()),
        ),
        NetworkStorageType(
            id = "ftp",
            title = "FTP",
            summary = "Plain FTP. Credentials cross the network unencrypted.",
            defaultPort = 21,
            fields = listOf(
                path(),
                passive(),
                NetworkField(
                    key = "anonymous",
                    label = "Anonymous login",
                    hint = "Ignores the username and password below",
                    kind = NetworkFieldKind.TOGGLE,
                ),
            ),
            note = "Plain FTP sends your password in clear text. Use SFTP or FTPS when the server offers them.",
        ),
        NetworkStorageType(
            id = "smb",
            title = "NAS (SMB/CIFS)",
            summary = "Windows and NAS shares over SMB.",
            defaultPort = 445,
            hostHint = "Server name or IP address",
            fields = listOf(
                NetworkField("share", "Share name", "e.g. backups", required = true),
                NetworkField("domain", "Domain or workgroup", "Optional"),
            ),
            note = "Android has no SMB stack built in, so Prism can store the share but cannot list it without a mount service.",
        ),
        NetworkStorageType(
            id = "nfs",
            title = "NFS",
            summary = "Unix exports, normally on a LAN.",
            defaultPort = 2049,
            fields = listOf(
                NetworkField("exportPath", "Export path", "/volume1", required = true),
                NetworkField("version", "NFS version", "3 or 4", kind = NetworkFieldKind.NUMBER, default = "4"),
            ),
            note = "NFS is a kernel mount on Android; Prism saves the export and leaves mounting to the platform.",
        ),
        NetworkStorageType(
            id = "afp",
            title = "AFP",
            summary = "Apple Filing Protocol volumes.",
            defaultPort = 548,
            fields = listOf(NetworkField("volume", "Volume name", "e.g. Time Machine", required = true)),
            note = "Apple deprecated AFP in favour of SMB; current macOS shares are better reached over SMB.",
        ),
        NetworkStorageType(
            id = "webdav",
            title = "WebDAV",
            summary = "HTTP folders: Nextcloud, ownCloud, Box, SharePoint.",
            defaultPort = 443,
            hostHint = "Host name, e.g. cloud.example.com",
            fields = listOf(
                NetworkField("basePath", "Base path", "/remote.php/dav/files/me", default = "/"),
                NetworkField(
                    key = "trustSelfSigned",
                    label = "Allow self-signed certificate",
                    hint = "Only for a server you control",
                    kind = NetworkFieldKind.TOGGLE,
                ),
            ),
            note = "WebDAV needs a PROPFIND request, which HttpURLConnection refuses; browsing is not wired up on this build.",
        ),
        NetworkStorageType(
            id = "s3",
            title = "Object Storage",
            summary = "S3-compatible buckets: AWS, MinIO, Backblaze, Wasabi.",
            defaultPort = 443,
            hostLabel = "Endpoint host",
            hostHint = "s3.amazonaws.com or your MinIO host",
            usesCredentials = false,
            fields = listOf(
                NetworkField("bucket", "Bucket", "e.g. prism-backups", required = true),
                NetworkField("region", "Region", "e.g. eu-west-1", default = "us-east-1"),
                NetworkField("accessKey", "Access key ID", "Required for private buckets"),
                NetworkField("secretKey", "Secret access key", "Required for private buckets", kind = NetworkFieldKind.SECRET),
                NetworkField("prefix", "Prefix", "Optional folder inside the bucket"),
                NetworkField(
                    key = "pathStyle",
                    label = "Path-style URLs",
                    hint = "Needed by MinIO and most self-hosted gateways",
                    kind = NetworkFieldKind.TOGGLE,
                ),
            ),
            note = "Prism signs S3 requests only when a transport is added; the bucket details are stored for that.",
        ),
        NetworkStorageType(
            id = "cloud",
            title = "Cloud Storage",
            summary = "A hosted drive reached through its own gateway.",
            defaultPort = 443,
            hostLabel = "Gateway host",
            hostHint = "e.g. drive.example.com",
            usesCredentials = false,
            fields = listOf(
                NetworkField("container", "Container or drive", "e.g. Prism", required = true),
                NetworkField("token", "Access token", "From the provider's developer console", kind = NetworkFieldKind.SECRET),
                NetworkField("endpoint", "Endpoint path", "/api/v1/files", default = "/"),
            ),
            note = "Every provider has its own authentication; the token is stored so a provider-specific transport can use it later.",
        ),
        NetworkStorageType(
            id = "iscsi",
            title = "SAN (iSCSI)",
            summary = "Block storage exported by a SAN target.",
            defaultPort = 3260,
            hostLabel = "Target host",
            hostHint = "IP address of the iSCSI portal",
            usesCredentials = false,
            fields = listOf(
                NetworkField("target", "Target IQN", "iqn.2024-01.com.example:storage", required = true),
                NetworkField("lun", "LUN", "0", kind = NetworkFieldKind.NUMBER, default = "0"),
                NetworkField("chapUser", "CHAP user", "Optional"),
                NetworkField("chapSecret", "CHAP secret", "Optional", kind = NetworkFieldKind.SECRET),
            ),
            note = "iSCSI is a raw block device: Android can only attach it through its own initiator, which Prism does not ship.",
        ),
        NetworkStorageType(
            id = "dfs",
            title = "Distributed File Systems",
            summary = "HDFS, GlusterFS or CephFS through a gateway.",
            defaultPort = 8020,
            hostLabel = "Name node or gateway",
            hostHint = "hdfs://namenode or gluster gateway",
            fields = listOf(
                NetworkField("namespace", "Namespace or authority", "hdfs://namenode:8020", required = true),
                NetworkField("basePath", "Base path", "/", default = "/"),
            ),
            note = "Distributed file systems need their own client library; Prism stores the namespace so one can be added.",
        ),
        NetworkStorageType(
            id = "p2p",
            title = "Prism Mesh share",
            summary = "A folder shared by another Prism device on the mesh.",
            defaultPort = 47800,
            hostLabel = "Node ID or mesh address",
            hostHint = "The peer's Prism node ID",
            fields = listOf(
                NetworkField("share", "Share name", "e.g. Family photos", required = true),
                NetworkField("token", "Mesh access token", "Optional; only for a private share", kind = NetworkFieldKind.SECRET),
            ),
            note = "Mesh shares travel over the Prism tunnel, so both devices must be on the same meshnet.",
        ),
        NetworkStorageType(
            id = "rsync",
            title = "rsync",
            summary = "An rsync daemon module.",
            defaultPort = 873,
            fields = listOf(
                NetworkField("module", "Module", "e.g. backup", required = true),
                NetworkField("path", "Sub-path", "Optional folder inside the module"),
            ),
        ),
        NetworkStorageType(
            id = "tftp",
            title = "TFTP",
            summary = "Trivial FTP: routers, switches and PXE images.",
            defaultPort = 69,
            usesCredentials = false,
            fields = listOf(
                NetworkField("path", "Remote path", "/", default = "/"),
                NetworkField("blockSize", "Block size", "512", kind = NetworkFieldKind.NUMBER, default = "512"),
            ),
            note = "TFTP has no authentication and no directory listing; it can fetch a known file and nothing more.",
        ),
        NetworkStorageType(
            id = "http",
            title = "HTTP directory",
            summary = "A plain web server with directory indexes.",
            defaultPort = 80,
            hostHint = "Host name or IP address",
            fields = listOf(NetworkField("basePath", "Base path", "/", default = "/")),
        ),
    )

    fun byId(id: String): NetworkStorageType? =
        all.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    /** The type for a saved connection, or null when it was written by a newer build. */
    fun forStorage(storage: PrismSettings.NetworkStorage): NetworkStorageType? = byId(storage.protocol)

    /** A human label for a protocol id, even one this build does not know. */
    fun titleFor(protocol: String): String =
        byId(protocol)?.title ?: protocol.uppercase()

    /**
     * Everything that would stop this connection from being usable, in the order the form shows it.
     *
     * Empty means saveable. Toggles are never problems: "false" is a decision, not a missing value.
     */
    fun problems(
        type: NetworkStorageType,
        name: String,
        host: String,
        port: Int,
        extras: Map<String, String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        if (name.isBlank()) problems += "Give the connection a name."
        if (host.isBlank()) problems += "Enter the ${type.hostLabel.lowercase()}."
        if (port !in 1..65535) problems += "The port must be between 1 and 65535."
        for (field in type.fields) {
            if (!field.required) continue
            if ((extras[field.key] ?: field.default).isBlank()) {
                problems += "${field.label} is needed for ${type.title}."
            }
        }
        return problems
    }

    /** Assembles the stored form from the form's values. Blank optional extras are dropped. */
    fun build(
        type: NetworkStorageType,
        name: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        extras: Map<String, String>,
    ): PrismSettings.NetworkStorage {
        // A field the chosen type does not have is dropped rather than carried over: a leftover
        // SFTP path on an S3 connection would look like configuration that does nothing.
        val kept = type.fields.mapNotNull { field ->
            val value = extras[field.key]?.trim().orEmpty()
            when {
                field.kind == NetworkFieldKind.TOGGLE -> field.key to (value.ifBlank { field.default })
                value.isBlank() -> null
                else -> field.key to value
            }
        }.toMap()
        val label = host.trim().ifBlank { type.title }
        return PrismSettings.NetworkStorage(
            name = name.trim().ifBlank { label },
            protocol = type.id,
            host = host.trim(),
            port = port,
            username = if (type.usesCredentials) username.trim() else "",
            password = if (type.usesCredentials) password else "",
            extras = kept,
        )
    }

    // ── Extras encoding ───────────────────────────────────────────────────────────────────────

    /**
     * Encodes the extras map into one settings string.
     *
     * PERCENT-ENCODED, because this is glued into a `;;;`/`::` record. Without it a password
     * containing `&` or a Windows path with `:` would split the record and turn one connection into
     * two broken ones.
     */
    fun encodeExtras(values: Map<String, String>): String =
        values.entries
            .filter { it.key.isNotBlank() }
            .joinToString("&") { (key, value) -> "${escape(key)}=${escape(value)}" }

    fun decodeExtras(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split("&").mapNotNull { pair ->
            val split = pair.indexOf('=')
            if (split <= 0) return@mapNotNull null
            val key = unescape(pair.substring(0, split))
            if (key.isBlank()) return@mapNotNull null
            key to unescape(pair.substring(split + 1))
        }.toMap()
    }

    internal fun escape(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    internal fun unescape(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

/**
 * The settings representation of saved connections.
 *
 * ONE PLACE, because [com.prism.launcher.PrismSettings] writes this string and the Files page reads
 * it, and a mismatch between the two is a connection that silently disappears on the next launch.
 * The format is the same `;;;`-separated, `::`-separated record it has always been -- each field is
 * now escaped, so an address or password containing the separators no longer corrupts the list --
 * with an optional eighth field for protocol-specific extras. Seven-field records written by older
 * builds still decode.
 */
object NetworkStorageCodec {

    private const val ENTRY_SEPARATOR = ";;;"
    private const val FIELD_SEPARATOR = "::"

    fun encode(list: List<PrismSettings.NetworkStorage>): String =
        list.joinToString(ENTRY_SEPARATOR) { storage ->
            val core = listOf(
                storage.id,
                storage.name,
                storage.protocol,
                storage.host,
                storage.port.toString(),
                storage.username,
                storage.password,
            ).joinToString(FIELD_SEPARATOR) { NetworkStorageTypes.escape(it) }
            // The extras blob is already percent-encoded, so it cannot contain a separator.
            val extras = NetworkStorageTypes.encodeExtras(storage.extras)
            if (extras.isEmpty()) core else "$core$FIELD_SEPARATOR$extras"
        }

    fun decode(raw: String): List<PrismSettings.NetworkStorage> {
        if (raw.isBlank()) return emptyList()
        return raw.split(ENTRY_SEPARATOR).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < 7) return@mapNotNull null
            val name = NetworkStorageTypes.unescape(parts[1])
            PrismSettings.NetworkStorage(
                id = NetworkStorageTypes.unescape(parts[0]).ifBlank { UUID.randomUUID().toString() },
                name = name,
                protocol = NetworkStorageTypes.unescape(parts[2]),
                host = NetworkStorageTypes.unescape(parts[3]),
                // Older builds wrote the port with toInt(), so a corrupted record used to throw and
                // take the whole list with it. A bad port is one unusable row, not a crash.
                port = NetworkStorageTypes.unescape(parts[4]).toIntOrNull() ?: 0,
                username = NetworkStorageTypes.unescape(parts[5]),
                password = NetworkStorageTypes.unescape(parts[6]),
                extras = if (parts.size > 7) NetworkStorageTypes.decodeExtras(parts[7]) else emptyMap(),
            )
        }
    }
}
