# Making a backup of Conversations

This tutorial explains how you can backup your Conversations data. 

**WARNING**: Do not use the restore backup feature in an attempt to clone (run simultaneously) an installation. Restoring a backup is only meant for migrations or in case you’ve lost the original device.

1. Make sure that you know the password to your account(s)! You will need it later to decrypt your backup.
2. Deactivate all your account(s): on the chat screen, tap on the three buttons in the upper right, and go to "manage accounts".
3. Go back to Settings, scroll down until you find the option to create a new backup. Tap on that option.
4. Wait, until the notification tells you that the backup is finished.
5. Move the backup to whatever location you feel save with.

Done!

## Further information / troubleshooting
### Unable to decrypt 
This backup method will include your OMEMO keys. Due to forward secrecy you will not be able to recover messages sent and received between creating the backup and restoring it. If you have a server side archive (MAM) those messages will be retrieved but displayed as *unable to decrypt*. For technical reasons you might also lose the first message you either sent or receive after the restore; for each conversation you have. This message will then also show up as *unable to decrypt*, but this will automatically recover itself as long as both participants are on Conversations 2.3.11+. Note that this doesn’t happen if you just transfer to a new phone and no messages have been exchanged between backup and restore.

In the vast, vast majority of cases you won’t have to manually delete OMEMO keys or do anything like that. Conversations only introduced the official backup feature in 2.4.0 after making sure the *OMEMO self healing* mechanism introduced in 2.3.11 works fine.

### Media files could not be included
Backups only contain your media files if you enable *Include media files* in the backup settings. Even then Conversations may report that some files could not be included. This happens when a file still exists on your device but Android no longer lets the app read it.

Android remembers which app a file in shared storage belongs to, and only that app may read it again later. That link is lost when the file is copied back from a computer or another device, and also when Conversations is reinstalled or its app data is deleted — the files stay on the device, but they no longer belong to the freshly installed app.

In practice this affects documents and other non-media attachments, which are kept in your *Download* folder. Photos, videos and audio remain readable even when Android considers them to belong to another app, so those are still included in the backup.

The affected paths are written to the log. You can list them with `adb logcat -s conversations | grep backup-skipped`. Ownership can be handed back to Conversations per file:

```
adb shell "content update --uri content://media/external/file \
  --bind owner_package_name:s:eu.siacs.conversations \
  --where \"_data='/storage/emulated/0/Download/Conversations/example.pdf'\""
```

The quoting matters: the command is processed by the shell on your computer *and* by the shell on the phone, and without the escaped quotes a path containing a space is split apart. On a device with several user profiles add `--user <id>`, because each profile has its own media index.

Do this **before** you wipe the old device: unlike a file that is merely missing after a restore, a file that cannot be read is not in the backup at all, so there is nothing to restore it from.

### Media files could not be restored
After restoring, Conversations may report that some files were already on this device and could not be overwritten. This happens when a file of the same name is still there from before and Android considers it to belong to a different app, which also prevents Conversations from replacing it.

If such a file can still be read it is kept and used as it is, and your chat looks as expected. If it cannot be read, the attachment stays unusable until you hand ownership back with the same command as above. Tap *Share file list* on the notification to get the affected paths, or list them with `adb logcat -s conversations | grep restore-issue`.

Files that were neither part of the backup nor present on this device are marked as deleted; those are gone for good.
