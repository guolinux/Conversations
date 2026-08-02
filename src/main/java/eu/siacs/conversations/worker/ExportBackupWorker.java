package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.gson.stream.JsonWriter;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.Random;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class ExportBackupWorker extends Worker {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US);

    private static final String KEY_TYPE = "AES";
    private static final String CIPHER_MODE = "AES/GCM/NoPadding";
    private static final String PROVIDER = "BC";

    public static final String MIME_TYPE = "application/vnd.conversations.backup";

    private static final int NOTIFICATION_ID = 19;
    private static final int BACKUP_CREATED_NOTIFICATION_ID = 23;

    public static final String DB_ENTRY_NAME = "db.json";
    public static final String MEDIA_ENTRY_PREFIX = "media/";

    private static final int PENDING_INTENT_FLAGS =
            s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;

    private final boolean recurringBackup;
    private final boolean includeMedia;

    /** files that could not be read and are therefore missing from the archive */
    private final List<SkippedFile> skipped = new ArrayList<>();

    /**
     * @param unreadable the file is still on the device but we are not allowed to open it, as
     *     opposed to being gone entirely. Only the former is something the user can act on.
     */
    private record SkippedFile(File file, boolean unreadable) {}

    public ExportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.recurringBackup = inputData.getBoolean("recurring_backup", false);
        this.includeMedia = inputData.getBoolean("include_media", false);
    }

    @NonNull
    @Override
    public Result doWork() {
        // a recurring backup can run before anything has started XmppConnectionService, which is
        // the only place that creates the notification channels
        if (Compatibility.twentySix()) {
            NotificationService.initializeChannels(getApplicationContext());
        }
        setForegroundAsync(getForegroundInfo());
        final List<Uri> files;
        try {
            files = export();
        } catch (final IOException
                | InvalidKeySpecException
                | InvalidAlgorithmParameterException
                | InvalidKeyException
                | NoSuchPaddingException
                | NoSuchAlgorithmException
                | NoSuchProviderException e) {
            Log.d(Config.LOGTAG, "could not create backup", e);
            return Result.failure();
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }
        Log.d(Config.LOGTAG, "done creating " + files.size() + " backup files");
        final var appSettings = new AppSettings(getApplicationContext());
        final int previouslySkipped = appSettings.getBackupSkippedFiles();
        appSettings.setBackupSkippedFiles(this.skipped.size());
        if (files.isEmpty()) {
            return Result.success();
        }
        if (recurringBackup) {
            // a scheduled backup should not nag on every run about something the user may not be
            // able to fix. Only speak up when the situation actually changed; the count is also
            // shown in the backup settings either way.
            if (!this.skipped.isEmpty() && this.skipped.size() != previouslySkipped) {
                notifySuccess(files);
            }
            return Result.success();
        }
        notifySuccess(files);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        Log.d(Config.LOGTAG, "getForegroundInfo()");
        final NotificationCompat.Builder notification = getNotification();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification.build());
        }
    }

    private List<Uri> export()
            throws IOException,
                    InvalidKeySpecException,
                    InvalidAlgorithmParameterException,
                    InvalidKeyException,
                    NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    NoSuchProviderException {
        final Context context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final var backupLocation = appSettings.getBackupLocation();
        final var database = DatabaseBackend.getInstance(context);
        final var accounts = database.getAccounts();

        int count = 0;
        final int max = accounts.size();
        final ImmutableList.Builder<Uri> locations = new ImmutableList.Builder<>();
        Log.d(Config.LOGTAG, "starting backup for " + max + " accounts");
        for (final Account account : accounts) {
            if (isStopped()) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            final String password = account.getPassword();
            if (Strings.nullToEmpty(password).trim().isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "skipping backup for %s because password is empty. unable to"
                                        + " encrypt",
                                account.getJid().asBareJid()));
                count++;
                continue;
            }
            final Uri uri;
            try {
                uri = export(database, account, password, backupLocation, max, count);
            } catch (final WorkStoppedException e) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            locations.add(uri);
            count++;
        }
        return locations.build();
    }

    private Uri export(
            final DatabaseBackend database,
            final Account account,
            final String password,
            final Uri backupLocation,
            final int max,
            final int count)
            throws IOException,
                    InvalidKeySpecException,
                    InvalidAlgorithmParameterException,
                    InvalidKeyException,
                    NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    NoSuchProviderException,
                    WorkStoppedException {
        final var context = getApplicationContext();
        Log.d(
                Config.LOGTAG,
                String.format(
                        "exporting data for account %s (%s)",
                        account.getJid().asBareJid(), account.getUuid()));
        final byte[] IV = new byte[12];
        final byte[] salt = new byte[16];
        Random.SECURE_RANDOM.nextBytes(IV);
        Random.SECURE_RANDOM.nextBytes(salt);
        final BackupFileHeader backupFileHeader =
                new BackupFileHeader(
                        context.getString(R.string.app_name),
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt,
                        this.includeMedia);
        final var notification = getNotification();
        final var cancelPendingIntent =
                WorkManager.getInstance(context).createCancelPendingIntent(getId());
        notification.addAction(
                new NotificationCompat.Action.Builder(
                                R.drawable.ic_cancel_24dp,
                                context.getString(R.string.cancel),
                                cancelPendingIntent)
                        .build());
        final Progress progress = new Progress(notification, max, count);
        final String filename =
                String.format(
                        "%s.%s.ceb",
                        account.getJid().asBareJid().toString(), DATE_FORMAT.format(new Date()));
        final OutputStream outputStream;
        final Uri location;
        if ("file".equalsIgnoreCase(backupLocation.getScheme())) {
            final File file = new File(backupLocation.getPath(), filename);
            final File directory = file.getParentFile();
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath());
            }
            outputStream = new FileOutputStream(file);
            location = Uri.fromFile(file);
        } else {
            final var tree = DocumentFile.fromTreeUri(context, backupLocation);
            if (tree == null) {
                throw new IOException(
                        String.format(
                                "DocumentFile.fromTreeUri returned null for %s", backupLocation));
            }
            final var file = tree.createFile(MIME_TYPE, filename);
            if (file == null) {
                throw new IOException(
                        String.format("Could not create %s in %s", filename, backupLocation));
            }
            location = file.getUri();
            outputStream = context.getContentResolver().openOutputStream(location);
        }
        final DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        backupFileHeader.write(dataOutputStream);
        dataOutputStream.flush();

        final Cipher cipher =
                Compatibility.twentyEight()
                        ? Cipher.getInstance(CIPHER_MODE)
                        : Cipher.getInstance(CIPHER_MODE, PROVIDER);
        final byte[] key = getKey(password, salt);
        SecretKeySpec keySpec = new SecretKeySpec(key, KEY_TYPE);
        IvParameterSpec ivSpec = new IvParameterSpec(IV);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher);

        final ZipOutputStream zipOutputStream = new ZipOutputStream(cipherOutputStream);
        zipOutputStream.putNextEntry(new ZipEntry(DB_ENTRY_NAME));
        final JsonWriter jsonWriter =
                new JsonWriter(new OutputStreamWriter(zipOutputStream, StandardCharsets.UTF_8));
        jsonWriter.beginArray();
        final SQLiteDatabase db = database.getReadableDatabase();
        final String uuid = account.getUuid();
        accountExport(db, uuid, jsonWriter);
        simpleExport(db, Conversation.TABLENAME, Conversation.ACCOUNT, uuid, jsonWriter);
        final List<MediaExportEntry> mediaExportEntries =
                messageExport(db, uuid, location, jsonWriter, progress);
        for (final String table :
                Arrays.asList(
                        SQLiteAxolotlStore.PREKEY_TABLENAME,
                        SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                        SQLiteAxolotlStore.SESSION_TABLENAME,
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME)) {
            throwIfWorkStopped(location);
            simpleExport(db, table, SQLiteAxolotlStore.ACCOUNT, uuid, jsonWriter);
        }
        jsonWriter.endArray();
        jsonWriter.flush();
        zipOutputStream.closeEntry();
        if (!mediaExportEntries.isEmpty()) {
            mediaExport(zipOutputStream, location, mediaExportEntries, progress);
        }
        zipOutputStream.close();
        final var path = location.getPath();
        if ("file".equalsIgnoreCase(location.getScheme()) && path != null) {
            mediaScannerScanFile(new File(path));
        }
        Log.d(Config.LOGTAG, "written backup to " + location);
        return location;
    }

    private void mediaExport(
            final ZipOutputStream zipOutputStream,
            final Uri location,
            final List<MediaExportEntry> mediaExportEntries,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        // attachments are already-compressed formats (jpg, mp4, ogg, ...); deflating them again
        // wastes CPU for essentially no size benefit
        zipOutputStream.setLevel(Deflater.NO_COMPRESSION);
        long totalBytes = 0;
        for (final MediaExportEntry entry : mediaExportEntries) {
            totalBytes += entry.file.length();
        }
        final byte[] buffer = new byte[8192];
        long written = 0;
        long lastUpdate = 0;
        int p = Integer.MIN_VALUE;
        for (final MediaExportEntry entry : mediaExportEntries) {
            throwIfWorkStopped(location);
            // open before creating the entry: if the file turned unreadable since messageExport
            // checked it, this way no (zero length) entry ends up in the archive at all
            final InputStream inputStream;
            try {
                inputStream = new FileInputStream(entry.file);
            } catch (final IOException e) {
                skip(entry.file);
                continue;
            }
            zipOutputStream.putNextEntry(
                    new ZipEntry(MEDIA_ENTRY_PREFIX + entry.uuid + "/" + entry.file.getName()));
            try (final InputStream is = inputStream) {
                int read;
                while ((read = is.read(buffer)) != -1) {
                    zipOutputStream.write(buffer, 0, read);
                    written += read;
                    final int percentage =
                            totalBytes == 0 ? 100 : (int) (written * 100 / totalBytes);
                    if (p < percentage && (SystemClock.elapsedRealtime() - lastUpdate) > 2_000) {
                        p = percentage;
                        lastUpdate = SystemClock.elapsedRealtime();
                        notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                    }
                }
            }
            zipOutputStream.closeEntry();
        }
    }

    /**
     * Records a file that could not be put into the archive. Logged in a greppable key=value form
     * so the affected paths can be extracted with {@code adb logcat -s conversations | grep
     * backup-skipped} and fed into a script that repairs their MediaStore ownership.
     */
    private void skip(final File file) {
        // it is still there but we may not open it, as opposed to being gone: only the former is
        // something the user can repair
        final boolean unreadable = file.exists();
        Log.w(
                Config.LOGTAG,
                "backup-skipped reason="
                        + (unreadable ? "EACCES" : "ENOENT")
                        + " path="
                        + file.getAbsolutePath());
        this.skipped.add(new SkippedFile(file, unreadable));
    }

    private NotificationCompat.Builder getNotification() {
        final var context = getApplicationContext();
        final NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, "backup");
        notification
                .setContentTitle(context.getString(R.string.notification_create_backup_title))
                .setSmallIcon(R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false);
        notification.setOngoing(true);
        notification.setLocalOnly(true);
        return notification;
    }

    private void throwIfWorkStopped(final Uri location) throws WorkStoppedException {
        if (isStopped()) {
            if ("file".equalsIgnoreCase(location.getScheme())) {
                final var file = new File(location.getPath());
                if (file.delete()) {
                    Log.d(Config.LOGTAG, "deleted " + file.getAbsolutePath());
                }
            } else {
                final var documentFile =
                        DocumentFile.fromSingleUri(getApplicationContext(), location);
                if (documentFile != null && documentFile.delete()) {
                    Log.d(Config.LOGTAG, "deleted " + location);
                }
            }
            throw new WorkStoppedException();
        }
    }

    private void mediaScannerScanFile(final File file) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        getApplicationContext().sendBroadcast(intent);
    }

    private static void accountExport(
            final SQLiteDatabase db, final String uuid, final JsonWriter writer)
            throws IOException {
        try (final Cursor accountCursor =
                db.query(
                        Account.TABLENAME,
                        null,
                        Account.UUID + "=?",
                        new String[] {uuid},
                        null,
                        null,
                        null)) {
            while (accountCursor != null && accountCursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(Account.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                    final String name = accountCursor.getColumnName(i);
                    writer.name(name);
                    final String value = accountCursor.getString(i);
                    if (value == null
                            || Account.ROSTERVERSION.equals(accountCursor.getColumnName(i))) {
                        writer.nullValue();
                    } else if (Account.OPTIONS.equals(accountCursor.getColumnName(i))
                            && value.matches("\\d+")) {
                        int intValue = Integer.parseInt(value);
                        if (QuickConversationsService.isConversations()) {
                            intValue |= 1 << Account.OPTION_DISABLED;
                        }
                        writer.value(intValue);
                    } else {
                        writer.value(value);
                    }
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private static void simpleExport(
            final SQLiteDatabase db,
            final String table,
            final String column,
            final String uuid,
            final JsonWriter writer)
            throws IOException {
        try (final Cursor cursor =
                db.query(table, null, column + "=?", new String[] {uuid}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(table);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < cursor.getColumnCount(); ++i) {
                    final String name = cursor.getColumnName(i);
                    writer.name(name);
                    final String value = cursor.getString(i);
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private static boolean isFileMessageType(final int type) {
        return type == Message.TYPE_IMAGE
                || type == Message.TYPE_FILE
                || type == Message.TYPE_PRIVATE_FILE;
    }

    private List<MediaExportEntry> messageExport(
            final SQLiteDatabase db,
            final String uuid,
            final Uri location,
            final JsonWriter writer,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        final var context = getApplicationContext();
        final List<MediaExportEntry> mediaExportEntries = new ArrayList<>();
        try (final Cursor cursor =
                db.rawQuery(
                        "select messages.* from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid where"
                                + " conversations.accountUuid=?",
                        new String[] {uuid})) {
            final int size = cursor.getCount();
            Log.d(Config.LOGTAG, "exporting " + size + " messages for account " + uuid);
            final int typeIndex = cursor.getColumnIndexOrThrow(Message.TYPE);
            final int relativeFilePathIndex =
                    cursor.getColumnIndexOrThrow(Message.RELATIVE_FILE_PATH);
            final int uuidIndex = cursor.getColumnIndexOrThrow(Message.UUID);
            final int deletedIndex = cursor.getColumnIndexOrThrow(Message.DELETED);
            long lastUpdate = 0;
            int i = 0;
            int p = Integer.MIN_VALUE;
            while (cursor.moveToNext()) {
                throwIfWorkStopped(location);
                // Resolve the attachment (if any) and decide up front whether it makes it into
                // the archive. Readability rather than mere existence: stat() succeeds on files
                // we are not allowed to open (foreign MediaStore owner), and trusting exists()
                // here is what used to produce zero length entries.
                final Message.StorageLocation storageLocation;
                if (isFileMessageType(cursor.getInt(typeIndex))
                        && !Strings.isNullOrEmpty(cursor.getString(relativeFilePathIndex))) {
                    storageLocation = Message.storageLocationFromCursor(context, cursor);
                } else {
                    storageLocation = null;
                }
                final boolean embed =
                        includeMedia && storageLocation != null && storageLocation.file().canRead();
                // A row the app already knows is gone (the user deleted the attachment, or the
                // file observer noticed it disappeared) is not a backup problem and must not be
                // reported -- the message is already shown as deleted in the app.
                final boolean knownDeleted = cursor.getInt(deletedIndex) > 0;
                if (includeMedia && storageLocation != null && !embed && !knownDeleted) {
                    skip(storageLocation.file());
                }
                final String relativeFilePath;
                if (storageLocation == null) {
                    relativeFilePath = null;
                } else {
                    relativeFilePath =
                            FileBackend.relativizeFilePath(
                                    context,
                                    storageLocation.file(),
                                    storageLocation.sharedStorage());
                }
                writer.beginObject();
                writer.name("table");
                writer.value(Message.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int j = 0; j < cursor.getColumnCount(); ++j) {
                    final String name = cursor.getColumnName(j);
                    writer.name(name);
                    if (storageLocation != null && Message.RELATIVE_FILE_PATH.equals(name)) {
                        // never leak the source device's absolute path: it embeds the Android
                        // user id (and for app data the package name) and is meaningless
                        // elsewhere. Restore rederives the location for embedded files and
                        // resolves this relative path only for the ones we could not include.
                        if (relativeFilePath == null) {
                            writer.nullValue();
                        } else {
                            writer.value(relativeFilePath);
                        }
                    } else if (storageLocation != null && Message.DELETED.equals(name)) {
                        // in a backup the flag means "this archive does not carry the file",
                        // which is not necessarily the source device's own deleted state
                        writer.value(embed ? 0 : 1);
                    } else {
                        final String value = cursor.getString(j);
                        writer.value(value);
                    }
                }
                writer.endObject();
                writer.endObject();
                if (embed) {
                    mediaExportEntries.add(
                            new MediaExportEntry(
                                    cursor.getString(uuidIndex), storageLocation.file()));
                }
                final int percentage = i * 100 / size;
                if (p < percentage && (SystemClock.elapsedRealtime() - lastUpdate) > 2_000) {
                    p = percentage;
                    lastUpdate = SystemClock.elapsedRealtime();
                    notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                }
                i++;
                if (i % 500 == 0) {
                    Log.d(Config.LOGTAG, "flushing writer after " + i + " messages");
                    writer.flush();
                }
            }
        }
        return mediaExportEntries;
    }

    private static class MediaExportEntry {
        private final String uuid;
        private final File file;

        private MediaExportEntry(final String uuid, final File file) {
            this.uuid = uuid;
            this.file = file;
        }
    }

    public static byte[] getKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        final SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1024, 128))
                .getEncoded();
    }

    /**
     * Shares the skipped paths as plain text. Same information as the {@code backup-skipped} log
     * lines, for getting them off the device without adb.
     */
    private PendingIntent shareSkippedIntent(final Context context) {
        final var resources = context.getResources();
        // grouped by reason: only the unreadable ones can be repaired, the missing ones are gone
        final var text = new StringBuilder();
        for (final boolean unreadable : new boolean[] {true, false}) {
            final var group =
                    ImmutableList.copyOf(
                            Collections2.filter(this.skipped, f -> f.unreadable() == unreadable));
            if (group.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(
                            resources.getQuantityString(
                                    unreadable
                                            ? R.plurals.backup_skipped_unreadable
                                            : R.plurals.backup_skipped_missing,
                                    group.size(),
                                    group.size()))
                    .append(":\n");
            for (final SkippedFile skippedFile : group) {
                text.append(skippedFile.file().getAbsolutePath()).append('\n');
            }
        }
        final var intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
        return PendingIntent.getActivity(
                context,
                192,
                Intent.createChooser(intent, context.getString(R.string.share_skipped_files)),
                PENDING_INTENT_FLAGS);
    }

    private void notifySuccess(final List<Uri> locations) {
        final var context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final String path = appSettings.getBackupLocationAsPath();
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> uris = new ArrayList<>();
        for (final Uri uri : locations) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                uris.add(FileBackend.getUriForFile(context, new File(uri.getPath())));
            } else {
                uris.add(uri);
            }
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(MIME_TYPE);
        final Intent chooser =
                Intent.createChooser(intent, context.getString(R.string.share_backup_files));
        final var shareFilesIntent =
                PendingIntent.getActivity(context, 190, chooser, PENDING_INTENT_FLAGS);

        final String created =
                context.getString(R.string.notification_backup_created_subtitle, path);
        final var resources = context.getResources();
        final int unreadable = Collections2.filter(this.skipped, SkippedFile::unreadable).size();
        final int missing = this.skipped.size() - unreadable;

        // the failure comes first in both the collapsed and the expanded text: a warning behind
        // the (much longer) storage location note is easy to miss entirely
        // what happened, then how to see which files, then how to fix it. The reason and the
        // repair steps live in the help page rather than in the notification.
        final var bigText = new StringBuilder();
        if (unreadable > 0) {
            bigText.append(
                            resources.getQuantityString(
                                    R.plurals.backup_skipped_unreadable, unreadable, unreadable))
                    .append('.');
        }
        if (missing > 0) {
            if (bigText.length() > 0) {
                bigText.append(' ');
            }
            bigText.append(
                            resources.getQuantityString(
                                    R.plurals.backup_skipped_missing, missing, missing))
                    .append('.');
        }
        if (bigText.length() > 0) {
            // the button labels are passed in rather than written into the sentence, so a
            // translation can never end up referring to a button by a name it does not have
            bigText.append("\n\n")
                    .append(
                            context.getString(
                                    R.string.backup_skipped_files_list,
                                    context.getString(R.string.share_skipped_files)));
            // only point at the help button when there actually is one
            if (Config.HELP != null) {
                bigText.append(' ')
                        .append(
                                context.getString(
                                        R.string.backup_skipped_files_help,
                                        context.getString(R.string.help)));
            }
        }
        final String contentText;
        if (bigText.length() > 0) {
            contentText =
                    resources.getQuantityString(
                            R.plurals.backup_skipped_files,
                            this.skipped.size(),
                            this.skipped.size());
            bigText.append("\n\n").append(created);
        } else {
            contentText = created;
            bigText.append(created);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_backup_created_title))
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText.toString()))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_archive_24dp);

        builder.addAction(
                R.drawable.ic_share_24dp,
                context.getString(R.string.share_backup_files),
                shareFilesIntent);
        if (!this.skipped.isEmpty()) {
            builder.addAction(
                    R.drawable.ic_share_24dp,
                    context.getString(R.string.share_skipped_files),
                    shareSkippedIntent(context));
            if (Config.HELP != null) {
                builder.addAction(
                        R.drawable.ic_help_24dp,
                        context.getString(R.string.help),
                        PendingIntent.getActivity(
                                context,
                                191,
                                new Intent(Intent.ACTION_VIEW, Config.HELP),
                                PENDING_INTENT_FLAGS));
            }
        }
        builder.setLocalOnly(true);
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.notify(BACKUP_CREATED_NOTIFICATION_ID, builder.build());
    }

    private static class Progress {
        private final NotificationCompat.Builder notification;
        private final int max;
        private final int count;

        private Progress(
                final NotificationCompat.Builder notification, final int max, final int count) {
            this.notification = notification;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            notification.setProgress(max * 100, count * 100 + percentage, false);
            return notification.build();
        }
    }

    private static class WorkStoppedException extends Exception {}
}
