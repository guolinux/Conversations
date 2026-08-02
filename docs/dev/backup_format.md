# The .ceb backup file format

Conversations Encrypted Backup (`.ceb`) files are produced by `ExportBackupWorker` and consumed by
`ImportBackupWorker`; the file header is implemented in `BackupFileHeader`. This document describes
the on-disk format and the reasoning behind it. The code is normative — if the two disagree, the
code is right and this document is stale.

There is one file per account, named `<bare-jid>.<yyyy-MM-dd-HH-mm>.ceb`, with the media type
`application/vnd.conversations.backup`.

## Layout

```
+--------------------------------------+
| header (plaintext)                   |
+--------------------------------------+
| AES/GCM ciphertext                   |
|   +------------------------------+   |
|   | version 1, 2: gzip           |   |
|   | version 3:    zip            |   |
|   +------------------------------+   |
+--------------------------------------+
```

### Header

Written with `DataOutputStream`, so integers are big endian and strings use the two byte length
prefixed modified UTF-8 of `writeUTF`.

| field           | type       | notes                                                        |
|-----------------|------------|--------------------------------------------------------------|
| `version`       | `int`      | see below                                                     |
| `app`           | UTF        | `R.string.app_name`, e.g. `Conversations`, `Quicksy`          |
| `jid`           | UTF        | bare JID of the account                                       |
| `timestamp`     | `long`     | epoch millis                                                  |
| `iv`            | 12 bytes   | AES-GCM nonce                                                 |
| `salt`          | 16 bytes   | PBKDF2 salt                                                   |
| `includesMedia` | `boolean`  | **version 3+ only**                                           |

The header is deliberately *not* encrypted: the restore UI has to identify the account and decide
what to offer before the user has typed a password. It is also not covered by the GCM
authentication tag, so nothing in it may be trusted for anything security relevant.

### Encryption

The key is derived from the account's XMPP password with PBKDF2WithHmacSHA1, 1024 iterations,
128 bit key. AES/GCM/NoPadding with a 128 bit authentication tag. This means a backup can only be
restored by someone who knows the account password, and that an account whose password is unknown
to the app (empty) is skipped during export.

## Versions

| version | payload                                                                       |
|---------|-------------------------------------------------------------------------------|
| 1       | gzip of SQL statements, one per line                                          |
| 2       | gzip of a JSON array of rows                                                  |
| 3       | zip with a `db.json` entry (same JSON array) plus optional media entries      |

Readers accept versions 2 and 3; version 1 is only handled by the external `ceb2txt` tool. A file
whose version is newer than the reader is rejected outright rather than parsed optimistically.

### The JSON payload

A single array of objects, one per database row:

```json
[
  {"table": "accounts",      "values": {"uuid": "...", "username": "...", ...}},
  {"table": "conversations", "values": {...}},
  {"table": "messages",      "values": {...}}
]
```

Exported tables are `accounts`, `conversations`, `messages` and the OMEMO tables `prekeys`,
`signed_prekeys`, `sessions`, `identities`. On import both the table name and every column name are
validated against an allow list before they reach `db.insert`, because the values come from a file
that the app cannot vouch for.

### Media entries (version 3)

`db.json` is always the first entry. It is followed by zero or more

```
media/<message-uuid>/<filename>
```

entries, one per message that has an attachment the export was able to read. `<filename>` is the
file's real basename. The uuid segment exists so that two messages whose attachments happen to
share a filename cannot collide inside the archive. On import the entry name is validated against a
strict pattern and `.`, `..` and backslashes are rejected (zip slip).

Media entries are written with `Deflater.NO_COMPRESSION`; `db.json` uses the default level.

## Paths, and what the columns mean inside a backup

Two message columns are interpreted differently inside a backup than they are in the database:

**`relativeFilePath`** holds a path relative to its storage root instead of the absolute path the
database stores. The root is implied by `sharedStorage`:

| `sharedStorage` | root                                       | example                          |
|-----------------|--------------------------------------------|----------------------------------|
| `1`             | `Environment.getExternalStorageDirectory()` | `Pictures/Conversations/x.jpg`   |
| `0`             | `context.getFilesDir()`                     | `attachments/x.jpg`              |

Both roots embed the Android user id, and the private one also embeds the package name, so dropping
the root is what makes the value portable between devices, between user profiles, and across an
`applicationId` suffix.

**`deleted`** means *this archive does not carry the file*. That is not necessarily the source
device's own deleted state, and the source database is never modified to match.

### On restore

The destination is always recomputed for the restoring device; the stored path is never used to
place a file. Concretely:

- **File is in the archive** — the destination comes from the entry's `<filename>` via
  `FileBackend.getStorageLocation`, which honours the *restoring* device's "save to gallery"
  setting. `relativeFilePath` from the backup is not consulted at all.
- **File is not in the archive** — there is no filename to work from, so the stored relative path
  is resolved against this device's roots. It is only kept when the source had the file in shared
  storage *and* we are restoring into shared storage, i.e. when the user could plausibly have put
  the file back by hand. Otherwise it is dropped.

Rows are marked deleted up front and cleared again once a file has actually been placed, once one
already exists at the destination, or once `reconcileSharedStorage` finds a kept path that is
present and readable. Reconciliation is restricted to shared storage because private app data is
always readable and writable by the app, so a file missing there is genuinely gone.

Note that a file which exists but cannot be read is *not* flagged as deleted. `checkForDeletedFiles`
reconciles on existence alone, so it would clear such a flag again on the next service start
whether or not anything was fixed; flagging it would only produce a "file deleted" state that later
flips to a broken preview. Those files are reported in the notification after the restore instead,
because handing ownership back to the app is something the user has to do.

## Why it looks like this

**Zip rather than tar.gz.** `java.util.zip` is in the standard library; tar would mean either a new
dependency or hand rolled code. Memory is a wash since both stream. Per entry compression is the
real argument: `db.json` compresses well while attachments are already compressed formats, and a
single gzip wrapped around a tar cannot make that distinction. True store mode would avoid the
deflate call entirely but needs size and CRC up front, which costs an extra full read of every
attachment — worse than deflating at level zero for large videos.

**`includesMedia` lives in the plaintext header.** Media entries are inside the encrypted archive,
so there is no way to tell whether a backup carries media before the password has been entered —
yet the restore dialog has to decide whether offering a "save to gallery" choice makes sense. The
flag therefore only ever drives UI affordances. Restore behaviour is driven exclusively by the
entries actually found in the archive, which also makes the flag being unauthenticated harmless.
It reflects the export time opt-in, so a backup made with the option enabled but whose files were
all unreadable still reports `true` with an empty media section.

**Paths are relative, and rederived rather than converted.** An absolute path from the source
device is meaningless elsewhere, and leaks the user id. Rederiving from the filename also means a
restore automatically lands media wherever the *restoring* device wants it, rather than recreating
the source device's layout. The relative form is kept only as a fallback for files that are not in
the archive at all.

**The relative form must never reach the messages table.**
`Message.storageLocationFromCursor` treats any non-absolute value as a legacy bare filename and
resolves it against the pre scoped storage layout. Import therefore converts back to absolute
before inserting, gated on the header version: version 2 rows are inserted untouched so their bare
filenames keep working, and within version 3 every value is known to be the relative form.

**No content level deduplication.** Two messages that share one physical file each get their own
copy in the archive. Simpler, and the case is rare enough not to be worth a content addressed
layout.

**Unreadable attachments are omitted, not embedded empty.** The stream is opened before the zip
entry is created, so a file that cannot be read produces no entry rather than a zero length one.
Export decides embeddability by readability rather than existence, because `stat` succeeds on files
the app is not allowed to open.

Which attachments this actually hits is asymmetric, and worth knowing when reasoning about the
failure mode: an image or video that belongs to another app in MediaStore is still *readable*, so
it ends up in the archive normally. A non-media attachment in `Download/` is not — no permission
grants an app access to another app's non-media files there, only the storage access framework
does. In practice that makes documents the files that go missing from a backup, while photos and
videos survive.

## Known limitations

- Restoring always attempts to overwrite the destination, which is what reclaims ownership when it
  succeeds. It fails for a file that already exists and belongs to another app, and the existing
  file is then assumed to be the same attachment and kept. Whether that file is usable depends on
  what it is: foreign owned media stays readable, a foreign owned non-media file does not, and the
  attachment stays broken until the user hands ownership back.
- Both backup and restore report what happened in their completion notification: a count per
  category, a share action producing the affected paths grouped by category, and a link to the
  help page. Export additionally logs one `backup-skipped reason=... path=...` line per file, which
  is the machine readable form.
- An interrupted restore leaves the account configured with some media missing. Recovery is to
  delete the account and restore again.
- Notification channels are created by `XmppConnectionService`, which a restore never starts, and
  clearing the app data deletes them. Both workers therefore call
  `NotificationService.initializeChannels` themselves; posting to a channel that does not exist is
  silently dropped. For the same reason `ImportBackupActivity` asks for `POST_NOTIFICATIONS`, which
  the data wipe revoked.
