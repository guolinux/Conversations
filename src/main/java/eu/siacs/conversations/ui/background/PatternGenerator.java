package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

public class PatternGenerator {

    public enum PatternType {
        SOFT_GRADIENT,
        RADIAL_GRADIENT,
        SOFT_MESH,
        GRAINY_PAPER,
        FLOATING_SPHERES,
        SCRIBBLE_PRO,
        PLASMA
    }

    private static final Random random = new Random();

    public static Bitmap generate(Context ctx, PatternType type, int size, boolean isDark) {

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        switch (type) {

            case SOFT_GRADIENT:
                drawSoftGradient(canvas, size, isDark);
                break;

            case RADIAL_GRADIENT:
                drawRadialGradient(canvas, size, isDark);
                break;

            case GRAINY_PAPER:
                drawGrain(canvas, size, isDark);
                break;

            case FLOATING_SPHERES:
                drawSpheres(canvas, size, isDark);
                break;

            case SCRIBBLE_PRO:
                drawScribble(canvas, size, isDark);
                break;

            case SOFT_MESH:
                drawSoftMesh(canvas, size, isDark);
                break;

            case PLASMA:
                drawFastPlasma(canvas, size, isDark);
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

    private static void drawDiamondGrid(Canvas c, int size, boolean dark) {

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(dark ? 0x11FFFFFF : 0x22000000);
        p.setStrokeWidth(2f);

        int step = size / 20;

        for (int x = -size; x < size * 2; x += step) {
            c.drawLine(x, 0, x + size, size, p);
            c.drawLine(x, size, x + size, 0, p);
        }
    }

    private static void drawDottedNoise(Canvas c, int size, boolean dark) {

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(dark ? 0x22FFFFFF : 0x22000000);

        int dots = size * size / 1200;

        for (int i = 0; i < dots; i++) {
            float x = random.nextFloat() * size;
            float y = random.nextFloat() * size;
            c.drawPoint(x, y, p);
        }
    }

    private static void drawGrain(Canvas c, int size, boolean dark) {

        Paint p = new Paint();
        p.setColor(dark ? Color.BLACK : Color.WHITE);
        c.drawRect(0, 0, size, size, p);

        p.setColor(dark ? 0x11FFFFFF : 0x11000000);

        int points = size * size / 900;

        for (int i = 0; i < points; i++) {
            c.drawPoint(random.nextInt(size), random.nextInt(size), p);
        }
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

    private static void drawScribble(Canvas c, int size, boolean dark) {

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(dark ? 0x33FFFFFF : 0x33000000);

        for (int i = 0; i < 40; i++) {
            float x1 = random.nextFloat() * size;
            float y1 = random.nextFloat() * size;
            float x2 = random.nextFloat() * size;
            float y2 = random.nextFloat() * size;
            c.drawLine(x1, y1, x2, y2, p);
        }
    }

    private static void drawFastPlasma(Canvas c, int size, boolean dark) {

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {

                float v = (float) (
                        Math.sin(x * 0.02) +
                                Math.sin(y * 0.03) +
                                Math.sin((x + y) * 0.015)
                );

                v = (v + 3) / 6f;

                int r = (int) (dark ? v * 60 : v * 180);
                int g = (int) (dark ? v * 50 : v * 150);
                int b = (int) (dark ? v * 70 : v * 200);

                bmp.setPixel(x, y, Color.rgb(r, g, b));
            }
        }

        c.drawBitmap(bmp, 0, 0, null);
    }
}