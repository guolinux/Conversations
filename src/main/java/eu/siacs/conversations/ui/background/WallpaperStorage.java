package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

public class WallpaperStorage {

    public static Uri saveWallpaper(Context ctx, Bitmap bmp, String name) {
        File dir = new File(ctx.getFilesDir(), "wallpapers");
        if (!dir.exists()) dir.mkdirs();

        File f = new File(dir, name + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception e) {
            return null;
        }
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", f);
    }
}