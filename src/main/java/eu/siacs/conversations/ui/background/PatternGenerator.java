package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

public class PatternGenerator {

    public enum PatternType {
        SOFT_GRADIENT,
        RADIAL_GRADIENT,
        SILK_GRADIENT,
        SOFT_MESH,
        CALM_GRID,
        FLOATING_SPHERES,
        ROUNDED_BLOBS,
        FROSTED_NOISE
    }
    private static final Random random = new Random();

    public static Bitmap generate(Context ctx, PatternType type, int size, boolean dark) {

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        switch (type) {

            case SOFT_GRADIENT:
                drawSoftGradient(canvas, size, dark);
                break;

            case RADIAL_GRADIENT:
                drawRadialGradient(canvas, size, dark);
                break;

            case SILK_GRADIENT:
                drawSilkGradient(canvas, size, dark);
                break;

            case SOFT_MESH:
                drawSoftMesh(canvas, size, dark);
                break;

            case CALM_GRID:
                drawCalmGrid(canvas, size, dark);
                break;

            case FLOATING_SPHERES:
                drawSpheres(canvas, size, dark);
                break;

            case ROUNDED_BLOBS:
                drawRoundedBlobs(canvas, size, dark);
                break;

            case FROSTED_NOISE:
                drawFrostedNoise(canvas, size, dark);
                break;

        }

        return bmp;
    }


    private static void drawSoftGradient(Canvas c, int size, boolean dark) {

        int c1 = dark ? Color.parseColor("#222222") : Color.parseColor("#e8f2ff");
        int c2 = dark ? Color.parseColor("#111111") : Color.parseColor("#d7e4fa");

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader shader = new LinearGradient(
                0, 0,
                size, size,
                c1, c2,
                Shader.TileMode.CLAMP
        );

        p.setShader(shader);
        c.drawRect(0, 0, size, size, p);
    }

    private static void drawRadialGradient(Canvas c, int size, boolean dark) {

        int c1 = dark ? Color.parseColor("#1a1a1a") : Color.parseColor("#ffffff");
        int c2 = dark ? Color.parseColor("#000000") : Color.parseColor("#e6e6e6");

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Shader shader = new RadialGradient(
                size / 2f, size / 2f,
                size * 0.9f,
                c1, c2,
                Shader.TileMode.CLAMP
        );

        p.setShader(shader);
        c.drawRect(0, 0, size, size, p);
    }


    private static void drawSpheres(Canvas c, int size, boolean dark) {

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        int bg = dark ? Color.parseColor("#101010") : Color.parseColor("#fafafa");
        c.drawColor(bg);

        for (int i = 0; i < 6; i++) {
            float radius = size * (0.1f + random.nextFloat() * 0.2f);
            float x = random.nextFloat() * size;
            float y = random.nextFloat() * size;

            int color = Color.argb(80,
                    100 + random.nextInt(155),
                    100 + random.nextInt(155),
                    200 + random.nextInt(55)
            );

            p.setColor(color);
            c.drawCircle(x, y, radius, p);
        }
    }

    private static void drawSoftMesh(Canvas c, int size, boolean dark) {

        drawSoftGradient(c, size, dark);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(dark ? 0x10FFFFFF : 0x22000000);

        int step = size / 12;

        for (int i = 0; i < size; i += step) {
            c.drawLine(i, 0, i, size, p);
            c.drawLine(0, i, size, i, p);
        }
    }

    private static void drawSilkGradient(Canvas c, int size, boolean dark) {

        int[] colors = dark
                ? new int[]{0xFF1C1C1E, 0xFF2C2C2E}
                : new int[]{0xFFF2F4FF, 0xFFE6ECFF};

        Shader shader = new LinearGradient(
                0, 0, size, size,
                colors,
                null,
                Shader.TileMode.CLAMP
        );

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(shader);

        c.drawRect(0, 0, size, size, p);
    }
    private static void drawFrostedNoise(Canvas c, int size, boolean dark) {

        int base = dark ? Color.parseColor("#181818") : Color.parseColor("#ffffff");
        c.drawColor(base);

        Paint p = new Paint();
        p.setColor(dark ? 0x08FFFFFF : 0x08000000);

        Random r = new Random(42);

        for (int i = 0; i < size * size / 3000; i++) {
            c.drawPoint(
                    r.nextInt(size),
                    r.nextInt(size),
                    p
            );
        }
    }

    private static void drawRoundedBlobs(Canvas c, int size, boolean dark) {

        int bg = dark ? Color.parseColor("#101010") : Color.parseColor("#FAFAFA");
        c.drawColor(bg);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Random r = new Random(123);

        for (int i = 0; i < 5; i++) {
            float radius = size * (0.15f + r.nextFloat() * 0.15f);
            float x = r.nextFloat() * size;
            float y = r.nextFloat() * size;

            int color = dark
                    ? Color.argb(40, 200, 200, 255)
                    : Color.argb(60, 100, 150, 255);

            p.setColor(color);
            c.drawCircle(x, y, radius, p);
        }
    }

    private static void drawCalmGrid(Canvas c, int size, boolean dark) {

        int bg = dark ? Color.parseColor("#121212") : Color.parseColor("#FFFFFF");
        c.drawColor(bg);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(dark ? 0x10FFFFFF : 0x10000000);
        p.setStrokeWidth(1f);

        int step = size / 14;

        for (int i = 0; i <= size; i += step) {
            c.drawLine(i, 0, i, size, p);
            c.drawLine(0, i, size, i, p);
        }
    }


}