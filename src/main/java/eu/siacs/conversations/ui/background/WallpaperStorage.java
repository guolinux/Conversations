package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
public class WallpaperStorage {

    public static Uri saveWallpaper(Context context, Bitmap bmp, String fileName) {
        try {
            File dir = new File(context.getFilesDir(), "wallpapers");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            out.flush();
            out.close();

            return Uri.fromFile(file);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
