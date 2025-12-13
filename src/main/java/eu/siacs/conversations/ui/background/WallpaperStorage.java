package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class WallpaperStorage {

    public static Uri saveWallpaper(Context ctx, Bitmap bmp, String name) {
        File dir = new File(ctx.getFilesDir(), "wallpapers");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, name + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", f);
    }

    public static Uri saveUserWallpaper(Context ctx, Uri sourceUri, String name) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(sourceUri);
            Bitmap original = BitmapFactory.decodeStream(is);
            if (is != null) is.close();
            if (original == null) return null;

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            Bitmap scaled = Bitmap.createScaledBitmap(original, w, h, true);
            original.recycle();
            File dir = new File(ctx.getFilesDir(), "wallpapers");
            if (!dir.exists()) dir.mkdirs();

            File f = new File(dir, name + ".jpg");
            FileOutputStream out = new FileOutputStream(f);
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
            out.close();

            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", f);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}