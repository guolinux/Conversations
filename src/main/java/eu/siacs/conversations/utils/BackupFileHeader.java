package eu.siacs.conversations.utils;

import androidx.annotation.NonNull;
import eu.siacs.conversations.xmpp.Jid;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;

public class BackupFileHeader {

    /**
     * Version 2: header followed by AES/GCM(GZIP(JSON array)). Version 3: header (with an
     * additional includesMedia flag) followed by AES/GCM(ZIP), with a mandatory first "db.json"
     * entry (same JSON array payload as version 2) optionally followed by
     * "media/&lt;uuid&gt;/&lt;filename&gt;" entries for backed up attachments.
     */
    private static final int VERSION = 3;

    private static final int MIN_SUPPORTED_VERSION = 2;

    private final String app;
    private final Jid jid;
    private final long timestamp;
    private final byte[] iv;
    private final byte[] salt;
    private final int version;
    private final boolean includesMedia;

    @NonNull
    @Override
    public String toString() {
        return "BackupFileHeader{"
                + "app='"
                + app
                + '\''
                + ", jid="
                + jid
                + ", timestamp="
                + timestamp
                + ", iv="
                + CryptoHelper.bytesToHex(iv)
                + ", salt="
                + CryptoHelper.bytesToHex(salt)
                + ", version="
                + version
                + ", includesMedia="
                + includesMedia
                + '}';
    }

    public BackupFileHeader(
            String app, Jid jid, long timestamp, byte[] iv, byte[] salt, boolean includesMedia) {
        this(app, jid, timestamp, iv, salt, VERSION, includesMedia);
    }

    private BackupFileHeader(
            String app,
            Jid jid,
            long timestamp,
            byte[] iv,
            byte[] salt,
            int version,
            boolean includesMedia) {
        this.app = app;
        this.jid = jid;
        this.timestamp = timestamp;
        this.iv = iv;
        this.salt = salt;
        this.version = version;
        this.includesMedia = includesMedia;
    }

    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(VERSION);
        dataOutputStream.writeUTF(app);
        dataOutputStream.writeUTF(jid.asBareJid().toString());
        dataOutputStream.writeLong(timestamp);
        dataOutputStream.write(iv);
        dataOutputStream.write(salt);
        // version 3+ only. This is deliberately part of the (unencrypted) header so the restore
        // UI can tell whether a backup contains media before the password has been entered.
        dataOutputStream.writeBoolean(includesMedia);
    }

    public static BackupFileHeader read(DataInputStream inputStream) throws IOException {
        final int version = inputStream.readInt();
        final String app = inputStream.readUTF();
        final String jid = inputStream.readUTF();
        long timestamp = inputStream.readLong();
        final byte[] iv = new byte[12];
        inputStream.readFully(iv);
        final byte[] salt = new byte[16];
        inputStream.readFully(salt);
        if (version < MIN_SUPPORTED_VERSION) {
            throw new OutdatedBackupFileVersion();
        }
        if (version > VERSION) {
            throw new IllegalArgumentException(
                    "Backup File version was "
                            + version
                            + " but app only supports up to version "
                            + VERSION);
        }
        // the flag was introduced together with the version 3 zip container; version 2 backups
        // never contain media and do not have this byte
        final boolean includesMedia;
        if (version >= 3) {
            includesMedia = inputStream.readBoolean();
        } else {
            includesMedia = false;
        }
        return new BackupFileHeader(app, Jid.of(jid), timestamp, iv, salt, version, includesMedia);
    }

    public int getVersion() {
        return version;
    }

    /**
     * Whether this backup was created with the "include media files" option enabled. Note this
     * reflects the option as set at export time; a backup created with the option on but where
     * every referenced file was missing from disk will still report true (with no media entries in
     * the archive). Also note the header is not covered by the AES-GCM authentication tag, so this
     * value is not tamper proof -- it only drives UI affordances, never restore behavior, which is
     * always driven by the actual entries found in the archive.
     */
    public boolean includesMedia() {
        return includesMedia;
    }

    public byte[] getSalt() {
        return salt;
    }

    public byte[] getIv() {
        return iv;
    }

    public Jid getJid() {
        return jid;
    }

    public String getApp() {
        return app;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Instant getInstant() {
        return Instant.ofEpochMilli(this.timestamp);
    }

    public static class OutdatedBackupFileVersion extends RuntimeException {}
}
