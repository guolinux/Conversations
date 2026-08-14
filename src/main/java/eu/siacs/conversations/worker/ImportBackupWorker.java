package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CountingInputStream;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.json.Services;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.crypto.BadPaddingException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public class ImportBackupWorker extends Worker {

    public static final String TAG_IMPORT_BACKUP = "tag-import-backup";

    private static final String DATA_KEY_PASSWORD = "password";
    private static final String DATA_KEY_URI = "uri";
    private static final String DATA_KEY_INCLUDE_OMEMO = "omemo";

    private static final Collection<String> OMEMO_TABLE_LIST =
            Arrays.asList(
                    SQLiteAxolotlStore.PREKEY_TABLENAME,
                    SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                    SQLiteAxolotlStore.SESSION_TABLENAME,
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME);

    private static final List<String> TABLE_ALLOW_LIST =
            new ImmutableList.Builder<String>()
                    .add(Account.TABLENAME, Conversation.TABLENAME, Message.TABLENAME)
                    .addAll(OMEMO_TABLE_LIST)
                    .build();

    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$");

    // media/<message-uuid>/<filename>, filename must not contain a path separator (zip-slip
    // protection)
    private static final Pattern MEDIA_ENTRY_PATTERN =
            Pattern.compile("^" + ExportBackupWorker.MEDIA_ENTRY_PREFIX + "([^/]+)/([^/]+)$");

    private static final int NOTIFICATION_ID = 21;

    // deliberately not NOTIFICATION_ID + 2: that is 23, which is what ExportBackupWorker posts its
    // "backup created" notification to, so restoring right after a backup used to replace it
    private static final int BACKUP_RESTORED_NOTIFICATION_ID = 25;

    private static final int PENDING_INTENT_FLAGS =
            s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;

    private final String password;
    private final Uri uri;
    private final boolean includeOmemo;

    /** why an attachment did not end up as a file this app can use after the restore */
    private enum Outcome {
        /** the file was already there and we may not overwrite it, but we can read it */
        KEPT,
        /** the file was already there, we may neither overwrite nor read it */
        UNREADABLE,
        /** the archive carried it but it could not be written */
        FAILED,
        /** not in the archive and not on this device either */
        MISSING
    }

    private record RestoreIssue(File file, Outcome outcome) {}

    private final List<RestoreIssue> issues = new ArrayList<>();

    /** read from the header before anything else; only the issue wording depends on it */
    private int backupVersion;

    /**
     * Records an attachment the restore could not make usable. Logged in the same greppable
     * key=value form as the {@code backup-skipped} lines on the export side, so the paths can be
     * pulled with {@code adb logcat -s conversations | grep restore-issue} instead of going through
     * the share action on the device.
     */
    private void issue(final File file, final Outcome outcome) {
        Log.w(Config.LOGTAG, "restore-issue reason=" + outcome + " path=" + file.getAbsolutePath());
        this.issues.add(new RestoreIssue(file, outcome));
    }

    public ImportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.password = inputData.getString(DATA_KEY_PASSWORD);
        this.uri = Uri.parse(inputData.getString(DATA_KEY_URI));
        this.includeOmemo = inputData.getBoolean(DATA_KEY_INCLUDE_OMEMO, true);
    }

    @NonNull
    @Override
    public Result doWork() {
        // A restore typically runs on a freshly wiped app, where the notification channels do not
        // exist because nothing has started XmppConnectionService yet -- that service is the only
        // place that creates them, and ImportBackupActivity never binds it. Posting to a channel
        // that does not exist is silently dropped, which would lose both the progress and the
        // result notification.
        if (Compatibility.twentySix()) {
            NotificationService.initializeChannels(getApplicationContext());
        }
        setForegroundAsync(getForegroundInfo());
        final Result result;
        try {
            result = importBackup(this.uri, this.password);
        } catch (final FileNotFoundException e) {
            return failure(Reason.FILE_NOT_FOUND);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "error restoring backup " + uri, e);
            final Throwable throwable = e.getCause();
            if (throwable instanceof BadPaddingException || e instanceof ZipException) {
                return failure(Reason.DECRYPTION_FAILED);
            } else {
                return failure(Reason.GENERIC);
            }
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }

        return result;
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        final var notification = createImportBackupNotification(1, 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification);
        }
    }

    private Result importBackup(final Uri uri, final String password)
            throws IOException, InvalidKeySpecException {
        final var context = getApplicationContext();
        final var database = DatabaseBackend.getInstance(context);
        Log.d(Config.LOGTAG, "importing backup from " + uri);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final SQLiteDatabase db = database.getWritableDatabase();
        final InputStream inputStream;
        final String path = uri.getPath();
        final long fileSize;
        if ("file".equals(uri.getScheme()) && path != null) {
            final File file = new File(path);
            inputStream = new FileInputStream(file);
            fileSize = file.length();
        } else {
            final Cursor returnCursor =
                    context.getContentResolver().query(uri, null, null, null, null);
            if (returnCursor == null) {
                fileSize = 0;
            } else {
                returnCursor.moveToFirst();
                fileSize =
                        returnCursor.getLong(
                                returnCursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
                returnCursor.close();
            }
            inputStream = context.getContentResolver().openInputStream(uri);
        }
        if (inputStream == null) {
            return failure(Reason.FILE_NOT_FOUND);
        }
        final CountingInputStream countingInputStream = new CountingInputStream(inputStream);
        final DataInputStream dataInputStream = new DataInputStream(countingInputStream);
        final BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
        this.backupVersion = backupFileHeader.getVersion();
        Log.d(Config.LOGTAG, backupFileHeader.toString());

        final var accounts = database.getAccountAddresses();

        if (QuickConversationsService.isQuicksy() && !accounts.isEmpty()) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS);
        }

        if (accounts.contains(backupFileHeader.getJid())) {
            return failure(Reason.ACCOUNT_ALREADY_EXISTS);
        }

        final byte[] key = ExportBackupWorker.getKey(password, backupFileHeader.getSalt());

        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(
                false, new AEADParameters(new KeyParameter(key), 128, backupFileHeader.getIv()));
        final CipherInputStream cipherInputStream =
                new CipherInputStream(countingInputStream, cipher);

        if (backupFileHeader.getVersion() < 3) {
            final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream);
            final BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8));
            final JsonReader jsonReader = new JsonReader(reader);
            // version 2 stored absolute paths (re-rooted onto this device below) or, for very old
            // rows, a bare filename that getLegacyFileForFilename resolves
            importJsonRows(
                    db,
                    jsonReader,
                    backupFileHeader.getJid(),
                    password,
                    fileSize,
                    countingInputStream,
                    false);
            reportSharedStorage(db, backupFileHeader.getJid());
        } else {
            final ZipInputStream zipInputStream = new ZipInputStream(cipherInputStream);
            final ZipEntry firstEntry = zipInputStream.getNextEntry();
            if (firstEntry == null
                    || !ExportBackupWorker.DB_ENTRY_NAME.equals(firstEntry.getName())) {
                throw new IOException(
                        "Backup archive did not start with " + ExportBackupWorker.DB_ENTRY_NAME);
            }
            final BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(zipInputStream, StandardCharsets.UTF_8));
            final JsonReader jsonReader = new JsonReader(reader);
            importJsonRows(
                    db,
                    jsonReader,
                    backupFileHeader.getJid(),
                    password,
                    fileSize,
                    countingInputStream,
                    true);
            zipInputStream.closeEntry();
            importMedia(context, db, zipInputStream, fileSize, countingInputStream);
            reconcileSharedStorage(db, backupFileHeader.getJid());
        }
        final Jid jid = backupFileHeader.getJid();
        final Cursor countCursor =
                db.rawQuery(
                        "select count(messages.uuid) from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid join accounts on"
                                + " conversations.accountUuid=accounts.uuid where"
                                + " accounts.username=? and accounts.server=?",
                        new String[] {jid.getLocal(), jid.getDomain().toString()});
        countCursor.moveToFirst();
        final int count = countCursor.getInt(0);
        Log.d(Config.LOGTAG, String.format("restored %d messages in %s", count, stopwatch.stop()));
        countCursor.close();
        Conversations.getInstance(getApplicationContext()).resetAccounts();
        stopBackgroundService();
        notifySuccess();
        return Result.success();
    }

    private void importJsonRows(
            final SQLiteDatabase db,
            final JsonReader jsonReader,
            final Jid jid,
            final String password,
            final long fileSize,
            final CountingInputStream countingInputStream,
            final boolean portablePaths)
            throws IOException {
        if (jsonReader.peek() == JsonToken.BEGIN_ARRAY) {
            jsonReader.beginArray();
        } else {
            throw new IllegalStateException("Backup file did not begin with array");
        }
        db.beginTransaction();
        while (jsonReader.hasNext()) {
            if (jsonReader.peek() == JsonToken.BEGIN_OBJECT) {
                importRow(db, jsonReader, jid, password, portablePaths);
            } else if (jsonReader.peek() == JsonToken.END_ARRAY) {
                jsonReader.endArray();
                continue;
            }
            updateImportBackupNotification(fileSize, countingInputStream.getCount());
        }
        db.setTransactionSuccessful();
        db.endTransaction();
    }

    /**
     * Version 3 stores attachment paths relative to their storage root. Those must never reach the
     * messages table as-is: {@link Message#storageLocationFromCursor} reads any non-absolute value
     * as a legacy bare filename and would resolve it against the pre-scoped-storage layout. A path
     * is only kept where the user could plausibly have put the file back by hand, i.e. the source
     * had it in shared storage and we are restoring into shared storage too; otherwise the media
     * pass is the only thing that may supply one.
     *
     * <p>The row is marked deleted up front and cleared again by the media pass (or by {@link
     * #reconcileSharedStorage}), so an attachment that is not in the archive never renders as a
     * working one.
     */
    private void rewriteStorageLocation(final ContentValues values) {
        final var relativePath = values.getAsString(Message.RELATIVE_FILE_PATH);
        if (Strings.isNullOrEmpty(relativePath)) {
            return;
        }
        final var context = getApplicationContext();
        final var sourceShared = asBoolean(values.get(Message.SHARED_STORAGE));
        final var targetShared = new AppSettings(context).isUseSharedStorage();
        if (sourceShared && targetShared) {
            values.put(
                    Message.RELATIVE_FILE_PATH,
                    FileBackend.resolveFilePath(context, relativePath, true).getAbsolutePath());
        } else {
            values.putNull(Message.RELATIVE_FILE_PATH);
        }
        values.put(Message.DELETED, 1);
    }

    /**
     * Version 2 stores an absolute path, which names the storage root of the device and user
     * profile the backup was made on. Restoring into a different profile would leave every
     * attachment pointing at the source profile, so the path is re-rooted onto this device; the
     * part below the root is what the file keeps in either storage.
     *
     * <p>Rows that predate scoped storage store a bare filename instead, which {@link
     * Message#storageLocationFromCursor} resolves through the legacy layout. Those are left alone,
     * as is anything else we cannot place, and the column stays absolute either way -- a relative
     * value would be read back as one of those legacy filenames.
     *
     * <p>The deleted flag is left as the backup had it. Unlike version 3 there are no files in the
     * archive to reconcile against, and {@code checkForDeletedFiles} corrects the flag in both
     * directions on the next service start anyway.
     */
    private void rebaseStorageLocation(final ContentValues values) {
        final var path = values.getAsString(Message.RELATIVE_FILE_PATH);
        if (Strings.isNullOrEmpty(path) || path.charAt(0) != '/') {
            return;
        }
        final var rebased =
                FileBackend.rebaseFilePath(
                        getApplicationContext(),
                        path,
                        asBoolean(values.get(Message.SHARED_STORAGE)));
        if (rebased != null) {
            values.put(Message.RELATIVE_FILE_PATH, rebased.getAbsolutePath());
        }
    }

    /**
     * The exported values are raw cursor strings ("1"/"0") for most columns but real JSON numbers
     * for the ones we compute during export, so neither {@code getAsBoolean} (which only accepts
     * "true") nor a plain cast is reliable here.
     */
    private static boolean asBoolean(final Object value) {
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "1".equals(value.toString());
    }

    /**
     * Clears the deleted flag for attachments that we kept a path for and that turn out to be
     * present and readable on this device -- typically because the user copied them over by hand.
     * Restricted to shared storage: private app data is always readable and writable by us, so a
     * file missing there is genuinely gone.
     */
    private void reconcileSharedStorage(final SQLiteDatabase db, final Jid jid) {
        final var recovered = new ImmutableList.Builder<String>();
        final var gone = new ImmutableList.Builder<String>();
        try (final Cursor cursor =
                db.rawQuery(
                        "select messages.uuid,messages.relativeFilePath from messages join"
                            + " conversations on conversations.uuid=messages.conversationUuid join"
                            + " accounts on conversations.accountUuid=accounts.uuid where"
                            + " accounts.username=? and accounts.server=? and"
                            + " messages.relativeFilePath is not null and messages.sharedStorage=1"
                            + " and messages.deleted=1 and messages.type in (1,2,5)",
                        new String[] {jid.getLocal(), jid.getDomain().toString()})) {
            while (cursor.moveToNext()) {
                final var file = new File(cursor.getString(1));
                if (file.canRead()) {
                    // not in the archive, but the file is right there and usable
                    recovered.add(cursor.getString(0));
                } else if (file.exists()) {
                    // there but not ours to read. Same rule as in restoreMediaEntry: do not call
                    // it deleted, report it so the user can hand ownership back.
                    recovered.add(cursor.getString(0));
                    issue(file, Outcome.UNREADABLE);
                } else {
                    gone.add(cursor.getString(0));
                    issue(file, Outcome.MISSING);
                }
            }
        }
        final var recoveredUuids = recovered.build();
        final var goneUuids = gone.build();
        if (recoveredUuids.isEmpty() && goneUuids.isEmpty()) {
            return;
        }
        final var found = new ContentValues();
        found.put(Message.DELETED, 0);
        // The archive did not carry the file and it is not on this device either, so it is not
        // coming back. Drop the path so it stops being treated as an attachment at all -- keeping
        // it would make every future backup report the same file as missing again.
        final var lost = new ContentValues();
        lost.putNull(Message.RELATIVE_FILE_PATH);
        db.beginTransaction();
        try {
            for (final String uuid : recoveredUuids) {
                db.update(Message.TABLENAME, found, Message.UUID + "=?", new String[] {uuid});
            }
            for (final String uuid : goneUuids) {
                db.update(Message.TABLENAME, lost, Message.UUID + "=?", new String[] {uuid});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.d(
                Config.LOGTAG,
                "recovered "
                        + recoveredUuids.size()
                        + " attachments from shared storage, gave up on "
                        + goneUuids.size());
    }

    /**
     * Reports which attachments a version 2 restore leaves without a usable file. There is nothing
     * to place and nothing to reconcile against -- the archive carries no files -- but the paths
     * now point at this device, so we can at least say which ones are not there.
     *
     * <p>A missing file is flagged deleted while keeping its path, so dropping the file into place
     * and restarting the app recovers it through {@code checkForDeletedFiles}. Restricted to shared
     * storage for the same reason as {@link #reconcileSharedStorage}, and to rows the source device
     * did not already consider deleted, which are not news.
     */
    private void reportSharedStorage(final SQLiteDatabase db, final Jid jid) {
        final var missing = new ImmutableList.Builder<String>();
        try (final Cursor cursor =
                db.rawQuery(
                        "select messages.uuid,messages.relativeFilePath from messages join"
                            + " conversations on conversations.uuid=messages.conversationUuid join"
                            + " accounts on conversations.accountUuid=accounts.uuid where"
                            + " accounts.username=? and accounts.server=? and"
                            + " messages.relativeFilePath is not null and messages.sharedStorage=1"
                            + " and messages.deleted=0 and messages.type in (1,2,5)",
                        new String[] {jid.getLocal(), jid.getDomain().toString()})) {
            while (cursor.moveToNext()) {
                final var file = new File(cursor.getString(1));
                if (file.canRead()) {
                    continue;
                }
                if (file.exists()) {
                    // there but not ours to read. Nothing to change in the database: the file is
                    // present, so the app keeps referring to it, and handing ownership back is
                    // something only the user can do.
                    issue(file, Outcome.UNREADABLE);
                } else {
                    missing.add(cursor.getString(0));
                    issue(file, Outcome.MISSING);
                }
            }
        }
        final var missingUuids = missing.build();
        if (missingUuids.isEmpty()) {
            return;
        }
        final var values = new ContentValues();
        values.put(Message.DELETED, 1);
        db.beginTransaction();
        try {
            for (final String uuid : missingUuids) {
                db.update(Message.TABLENAME, values, Message.UUID + "=?", new String[] {uuid});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.d(Config.LOGTAG, "marked " + missingUuids.size() + " attachments as deleted");
    }

    /**
     * Restores media entries from a version-3+ backup archive. Runs after {@link #importJsonRows}
     * has already committed all DB rows, so every message referenced by a
     * media/&lt;uuid&gt;/&lt;filename&gt; entry already exists. Each file is written to a fresh
     * location computed from this (restoring) device's *current* gallery-mode setting (see {@link
     * FileBackend#getStorageLocation(Context, String, String)}) -- never from wherever the file
     * happened to live on the source device -- and its row is fixed up in its own small transaction
     * immediately after, so an interrupted restore leaves only the one in-flight entry
     * inconsistent, never anything already processed.
     */
    private void importMedia(
            final Context context,
            final SQLiteDatabase db,
            final ZipInputStream zipInputStream,
            final long fileSize,
            final CountingInputStream countingInputStream)
            throws IOException {
        final byte[] buffer = new byte[8192];
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            try {
                restoreMediaEntry(context, db, zipInputStream, entry, buffer);
            } catch (final IOException e) {
                Log.w(Config.LOGTAG, "could not restore media entry " + entry.getName(), e);
            } finally {
                zipInputStream.closeEntry();
            }
            updateImportBackupNotification(fileSize, countingInputStream.getCount());
        }
    }

    private void restoreMediaEntry(
            final Context context,
            final SQLiteDatabase db,
            final ZipInputStream zipInputStream,
            final ZipEntry entry,
            final byte[] buffer)
            throws IOException {
        final Matcher matcher = MEDIA_ENTRY_PATTERN.matcher(entry.getName());
        if (!matcher.matches()) {
            Log.w(Config.LOGTAG, "skipping unrecognized backup archive entry: " + entry.getName());
            return;
        }
        final String messageUuid = matcher.group(1);
        final String filename = matcher.group(2);
        if ("..".equals(filename) || ".".equals(filename) || filename.contains("\\")) {
            Log.w(Config.LOGTAG, "refusing to restore unsafe media entry: " + entry.getName());
            return;
        }
        final String mime =
                MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(filename));
        final Message.StorageLocation destination =
                FileBackend.getStorageLocation(context, filename, mime);
        final File destinationFile = destination.file();
        final File parent = destinationFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        // filenames are UUID-derived and restore only ever targets this app's own
        // Pictures|Movies|.../<app_name> folders, so a name collision between two unrelated
        // files is not a realistic concern here; if something is already sitting at this exact
        // path we treat it as this same attachment and (re-)write it rather than skip it, since
        // on scoped storage only the app that actually performs the write becomes the file's
        // MediaStore owner. Note this only succeeds when the file does not exist yet or we
        // already own it: overwriting a file owned by another app fails with EACCES, which is
        // handled below. A corrupted pre-existing file is not specially handled here; the user
        // can always delete the account and restore fresh.
        // not final: try-with-resources can still throw from close() after the copy loop finished,
        // in which case the file is not reliably on disk and the catch below resets this
        boolean written = false;
        try (final OutputStream outputStream = new FileOutputStream(destinationFile)) {
            int read;
            while ((read = zipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            written = true;
        } catch (final IOException e) {
            // Most likely EACCES because a file of that name already exists and belongs to another
            // app -- on scoped storage we cannot overwrite what we do not own, which is exactly the
            // codeberg issue #138 situation. Deliberately do NOT bail out here: the row still gets
            // the intended path recorded below, only flagged as deleted. Leaving relativeFilePath
            // NULL instead would be unrecoverable -- checkForDeletedFiles() only looks at rows
            // "where relativeFilePath is not null", and getFile() would fall back to a legacy
            // pre-scoped-storage path that does not exist. With the path recorded, the app heals
            // itself on the next service start as soon as the file becomes accessible again.
            Log.w(
                    Config.LOGTAG,
                    "could not write media file " + destinationFile.getAbsolutePath(),
                    e);
            written = false;
        }
        if (written) {
            if (destination.sharedStorage()) {
                mediaScannerScanFile(destinationFile);
            }
        } else if (!destinationFile.exists()) {
            // the archive carried the file but we could not put it anywhere
            issue(destinationFile, Outcome.FAILED);
        } else if (destinationFile.canRead()) {
            issue(destinationFile, Outcome.KEPT);
        } else {
            issue(destinationFile, Outcome.UNREADABLE);
        }
        final ContentValues values = new ContentValues();
        values.put(Message.RELATIVE_FILE_PATH, destinationFile.getAbsolutePath());
        values.put(Message.SHARED_STORAGE, destination.sharedStorage());
        // Recompute rather than carry over the exported flag, but only treat a file as deleted
        // when it is genuinely not there. A file that exists yet cannot be read is deliberately
        // not flagged: checkForDeletedFiles() reconciles on existence alone and would clear the
        // flag again on the next service start regardless of whether the user fixed anything, so
        // flagging it would only produce a "File deleted" that later flips to a broken bubble.
        // The restore notification reports these instead and points at the fix.
        values.put(Message.DELETED, !written && !destinationFile.exists());
        db.beginTransaction();
        try {
            db.update(Message.TABLENAME, values, Message.UUID + "=?", new String[] {messageUuid});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void mediaScannerScanFile(final File file) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        getApplicationContext().sendBroadcast(intent);
    }

    private void importRow(
            final SQLiteDatabase db,
            final JsonReader jsonReader,
            final Jid account,
            final String passphrase,
            final boolean portablePaths)
            throws IOException {
        jsonReader.beginObject();
        final String firstParameter = jsonReader.nextName();
        if (!firstParameter.equals("table")) {
            throw new IllegalStateException("Expected key 'table'");
        }
        final String table = jsonReader.nextString();
        if (!TABLE_ALLOW_LIST.contains(table)) {
            throw new IOException(String.format("%s is not recognized for import", table));
        }
        final ContentValues contentValues = new ContentValues();
        final String secondParameter = jsonReader.nextName();
        if (!secondParameter.equals("values")) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull();
                    contentValues.putNull(name);
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong());
                } else {
                    contentValues.put(name, jsonReader.nextString());
                }
            } else {
                throw new IOException(String.format("Unexpected column name %s", name));
            }
        }
        jsonReader.endObject();
        jsonReader.endObject();
        if (Account.TABLENAME.equals(table)) {
            final Jid jid =
                    Jid.of(
                            contentValues.getAsString(Account.USERNAME),
                            contentValues.getAsString(Account.SERVER),
                            null);
            final String password = contentValues.getAsString(Account.PASSWORD);
            if (QuickConversationsService.isQuicksy()) {
                if (!jid.getDomain().equals(Config.QUICKSY_DOMAIN)) {
                    throw new IOException("Trying to restore non Quicksy account on Quicksy");
                }
            }
            if (jid.equals(account) && passphrase.equals(password)) {
                Log.d(Config.LOGTAG, "jid and password from backup header had matching row");
            } else {
                throw new IOException("jid or password in table did not match backup");
            }
            final var keys = Account.Keys.parse(contentValues.getAsString(Account.KEYS));
            final var deviceId = keys.getAxolotlRegistrationId();
            final Account.Keys importReadyKeys;
            if (deviceId.isPresent() && this.includeOmemo) {
                importReadyKeys = new Account.Keys(deviceId.get());
            } else {
                importReadyKeys = new Account.Keys();
            }
            contentValues.put(Account.KEYS, Services.GSON.toJson(importReadyKeys));
        }
        if (Message.TABLENAME.equals(table)) {
            if (portablePaths) {
                rewriteStorageLocation(contentValues);
            } else {
                rebaseStorageLocation(contentValues);
            }
        }
        if (this.includeOmemo) {
            db.insert(table, null, contentValues);
        } else {
            if (OMEMO_TABLE_LIST.contains(table)) {
                if (SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table)
                        && contentValues.getAsInteger(SQLiteAxolotlStore.OWN) == 0) {
                    db.insert(table, null, contentValues);
                } else {
                    Log.d(Config.LOGTAG, "skipping over omemo key material in table " + table);
                }
            } else {
                db.insert(table, null, contentValues);
            }
        }
    }

    private void stopBackgroundService() {
        final var intent = new Intent(getApplicationContext(), XmppConnectionService.class);
        getApplicationContext().stopService(intent);
    }

    private void updateImportBackupNotification(final long total, final long current) {
        final int max;
        final int progress;
        if (total == 0) {
            max = 1;
            progress = 0;
        } else {
            max = 100;
            progress = (int) (current * 100 / total);
        }
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, createImportBackupNotification(max, progress));
    }

    private Notification createImportBackupNotification(final int max, final int progress) {
        final var context = getApplicationContext();
        final var builder = new NotificationCompat.Builder(getApplicationContext(), "backup");
        builder.setContentTitle(context.getString(R.string.restoring_backup))
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
                .setProgress(max, progress, max == 1 && progress == 0);
        return builder.build();
    }

    private List<RestoreIssue> issues(final Outcome outcome) {
        return ImmutableList.copyOf(
                Collections2.filter(this.issues, issue -> issue.outcome() == outcome));
    }

    private String describe(final Outcome outcome) {
        final var group = issues(outcome);
        if (group.isEmpty()) {
            return null;
        }
        // a version 2 archive never carried files, so wording about what the restore did or did
        // not place would be meaningless for the two outcomes it can produce
        final boolean mediaArchive = this.backupVersion >= 3;
        final int plural =
                switch (outcome) {
                    case UNREADABLE ->
                            mediaArchive
                                    ? R.plurals.restore_unreadable
                                    : R.plurals.restore_unreadable_v2;
                    case FAILED -> R.plurals.restore_failed;
                    case KEPT -> R.plurals.restore_kept;
                    case MISSING ->
                            mediaArchive ? R.plurals.restore_missing : R.plurals.restore_missing_v2;
                };
        return getApplicationContext()
                .getResources()
                .getQuantityString(plural, group.size(), group.size());
    }

    /**
     * Shares the affected paths grouped by outcome. Only the unreadable ones can be repaired by
     * handing ownership back to this app, so the grouping is the actionable part.
     */
    private PendingIntent shareIssuesIntent(final Context context) {
        final var text = new StringBuilder();
        for (final Outcome outcome : Outcome.values()) {
            final var group = issues(outcome);
            if (group.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(describe(outcome)).append(":\n");
            for (final RestoreIssue issue : group) {
                text.append(issue.file().getAbsolutePath()).append('\n');
            }
        }
        final var intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
        return PendingIntent.getActivity(
                context,
                146,
                Intent.createChooser(intent, context.getString(R.string.share_skipped_files)),
                PENDING_INTENT_FLAGS);
    }

    private void notifySuccess() {
        final var context = getApplicationContext();
        final var restored = context.getString(R.string.notification_restored_backup_subtitle);
        // most actionable first, same ordering as the notification after a backup: a warning
        // behind the "backup restored" line is easy to miss entirely
        final var lines = new ArrayList<String>();
        // ordered by how much the user can and should do about it
        for (final Outcome outcome :
                new Outcome[] {Outcome.UNREADABLE, Outcome.KEPT, Outcome.MISSING, Outcome.FAILED}) {
            final var line = describe(outcome);
            if (line != null) {
                lines.add(line + ".");
            }
        }

        final var builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_restored_backup_title))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_unarchive_24dp);
        if (lines.isEmpty()) {
            builder.setContentText(restored);
        } else {
            builder.setContentText(lines.get(0));
            final var bigText = new StringBuilder(Joiner.on(' ').join(lines));
            bigText.append("\n\n")
                    .append(
                            context.getString(
                                    R.string.backup_skipped_files_list,
                                    context.getString(R.string.share_skipped_files)));
            if (Config.HELP != null) {
                bigText.append(' ')
                        .append(
                                context.getString(
                                        R.string.backup_skipped_files_help,
                                        context.getString(R.string.help)));
            }
            bigText.append("\n\n").append(restored);
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText.toString()));
            builder.addAction(
                    R.drawable.ic_share_24dp,
                    context.getString(R.string.share_skipped_files),
                    shareIssuesIntent(context));
            if (Config.HELP != null) {
                builder.addAction(
                        R.drawable.ic_help_24dp,
                        context.getString(R.string.help),
                        PendingIntent.getActivity(
                                context,
                                147,
                                new Intent(Intent.ACTION_VIEW, Config.HELP),
                                PENDING_INTENT_FLAGS));
            }
        }
        if (QuickConversationsService.isConversations()
                && AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            builder.setContentIntent(
                    PendingIntent.getActivity(
                            context,
                            145,
                            new Intent(context, AccountUtils.MANAGE_ACCOUNT_ACTIVITY),
                            PENDING_INTENT_FLAGS));
        }
        Log.d(
                Config.LOGTAG,
                "posting restore notification, issues=" + this.issues.size() + " " + lines);
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(BACKUP_RESTORED_NOTIFICATION_ID, builder.build());
    }

    public static Data data(final String password, final Uri uri, final boolean includeOmemo) {
        return new Data.Builder()
                .putString(DATA_KEY_PASSWORD, password)
                .putString(DATA_KEY_URI, uri.toString())
                .putBoolean(DATA_KEY_INCLUDE_OMEMO, includeOmemo)
                .build();
    }

    private static Result failure(final Reason reason) {
        return Result.failure(new Data.Builder().putString("reason", reason.toString()).build());
    }

    public enum Reason {
        ACCOUNT_ALREADY_EXISTS,
        DECRYPTION_FAILED,
        FILE_NOT_FOUND,
        GENERIC;

        public static Reason valueOfOrGeneric(final String value) {
            if (Strings.isNullOrEmpty(value)) {
                return GENERIC;
            }
            try {
                return valueOf(value);
            } catch (final IllegalArgumentException e) {
                return GENERIC;
            }
        }
    }
}
