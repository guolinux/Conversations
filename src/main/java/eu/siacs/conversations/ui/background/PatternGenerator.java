package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
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
        SOFT_MESH,
        GRAINY_PAPER,
        DOTTED_NOISE,

        DIAMOND_GRID,
        FLOATING_SPHERES,
        PERLIN_LIKE_NOISE,
        SOFT_GRADIENT,
        SCRIBBLE_PRO,
        FIBER_TEXTURE
    }

    private static final Random rnd = new Random();

    public static Bitmap generate(Context ctx, PatternType type, int sizePx) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        boolean isDark = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        int base = isDark ? 18 : 245;
        canvas.drawColor(Color.rgb(base, base, base));

        int fg = isDark ? Color.argb(30, 255, 255, 255) : Color.argb(70, 0, 0, 0);

        int accent = isDark ? Color.argb(20, 255, 255, 255) : Color.argb(30, 0, 0, 0);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        switch (type) {
            case SOFT_MESH:
                drawSoftMesh(canvas, sizePx, isDark);
                break;

            case GRAINY_PAPER:
                drawGrainyPaper(canvas, sizePx, isDark);
                break;

           case DOTTED_NOISE:
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fg);
                int step = Math.max(16, sizePx / 24);
                float radius = Math.max(1.5f, sizePx / 160f);
                for (int x = 0; x < sizePx; x += step) {
                    for (int y = 0; y < sizePx; y += step) {
                        canvas.drawCircle(x + (step / 3f), y + (step / 3f), radius, paint);
                    }
                }
                break;

            case DIAMOND_GRID:
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(accent);
                paint.setStrokeWidth(Math.max(1f, sizePx / 600f));
                drawDiamondGrid(canvas, sizePx, paint);
                break;

            case FLOATING_SPHERES:
                drawFloatingSpheres(canvas, sizePx, isDark);
                break;

            case PERLIN_LIKE_NOISE:
                drawPerlinLike(canvas, sizePx, isDark);
                break;

            case SOFT_GRADIENT:
                drawSoftGradient(canvas, sizePx, isDark);
                break;

            case SCRIBBLE_PRO:
                paint.setColor(accent);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.2f, sizePx / 480f));
                for (int i = 0; i < sizePx / 6; i++) {
                    float x = rnd.nextInt(sizePx);
                    float y = rnd.nextInt(sizePx);
                    Path path = new Path();
                    path.moveTo(x, y);
                    for (int k = 0; k < 4; k++) {
                        path.rQuadTo(rnd.nextInt(40) - 20, rnd.nextInt(30) - 15, rnd.nextInt(40) - 20, rnd.nextInt(30) - 15);
                    }
                    canvas.drawPath(path, paint);
                }
                break;

            case FIBER_TEXTURE:
                drawFiberTexture(canvas, sizePx, isDark);
                break;
        }

        return bmp;
    }


    private static void drawSoftMesh(Canvas canvas, int sizePx, boolean isDark) {
        int c1 = isDark ? Color.rgb(20, 30, 40) : Color.rgb(245, 250, 255);
        int c2 = isDark ? Color.rgb(30, 40, 50) : Color.rgb(230, 240, 250);
        int c3 = isDark ? Color.rgb(24, 34, 44) : Color.rgb(250, 245, 240);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader lg = new LinearGradient(0, 0, sizePx, sizePx,
                new int[]{c1, c2, c3}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);

        p.setShader(lg);
        canvas.drawRect(0, 0, sizePx, sizePx, p);

        p.setShader(null);
        p.setColor(isDark ? Color.argb(25, 255, 255, 255) : Color.argb(18, 0, 0, 0));
        for (int i = 0; i < 12; i++) {
            float cx = rnd.nextInt(sizePx);
            float cy = rnd.nextInt(sizePx);
            float r = rnd.nextInt(sizePx / 3) + sizePx / 10f;
            canvas.drawCircle(cx, cy, r, p);
        }
    }

    private static void drawGrainyPaper(Canvas canvas, int sizePx, boolean isDark) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int base = isDark ? Color.rgb(18, 18, 18) : Color.rgb(250, 248, 244);
        canvas.drawColor(base);

        for (int pass = 0; pass < 3; pass++) {
            int density = sizePx * (pass + 1) / 3;
            for (int i = 0; i < density; i++) {
                p.setColor(isDark
                        ? Color.argb(rnd.nextInt(40), 255, 255, 255)
                        : Color.argb(rnd.nextInt(60), 0, 0, 0));
                float x = rnd.nextFloat() * sizePx;
                float y = rnd.nextFloat() * sizePx;
                canvas.drawCircle(x, y, rnd.nextFloat() * 1.4f + 0.3f, p);
            }
        }

        p.setColor(isDark ? Color.argb(18, 255, 255, 255) : Color.argb(12, 0, 0, 0));
        for (int i = 0; i < 8; i++) {
            float cx = rnd.nextFloat() * sizePx;
            float cy = rnd.nextFloat() * sizePx;
            float r = sizePx * (0.06f + rnd.nextFloat() * 0.12f);
            canvas.drawCircle(cx, cy, r, p);
        }
    }
    private static void drawDiamondGrid(Canvas canvas, int sizePx, Paint paint) {
        float step = Math.max(40, sizePx / 12f);
        for (float x = -sizePx; x < sizePx * 2; x += step) {
            for (float y = -sizePx; y < sizePx * 2; y += step) {
                Path path = new Path();
                float cx = x, cy = y;
                path.moveTo(cx, cy - step / 2);
                path.lineTo(cx + step / 2, cy);
                path.lineTo(cx, cy + step / 2);
                path.lineTo(cx - step / 2, cy);
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }

    private static void drawFloatingSpheres(Canvas canvas, int sizePx, boolean isDark) {
        int count = Math.max(6, sizePx / 80);
        for (int i = 0; i < count; i++) {
            float cx = rnd.nextFloat() * sizePx;
            float cy = rnd.nextFloat() * sizePx;
            float r = sizePx * (0.08f + rnd.nextFloat() * 0.18f);

            RadialGradient rg = new RadialGradient(cx, cy, r,
                    (isDark ? Color.argb(25, 255, 255, 255) : Color.argb(30, 0, 0, 0)),
                    Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(rg);
            canvas.drawCircle(cx, cy, r, p);
        }
    }

    private static void drawPerlinLike(Canvas canvas, int sizePx, boolean isDark) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int layer = 0; layer < 5; layer++) {
            int dots = sizePx * (10 + layer * 10);
            for (int i = 0; i < dots; i++) {
                p.setColor(isDark ? Color.argb(rnd.nextInt(28), 255, 255, 255) :
                        Color.argb(rnd.nextInt(38), 0, 0, 0));
                float x = rnd.nextFloat() * sizePx;
                float y = rnd.nextFloat() * sizePx;
                float r = (layer + 1) * (rnd.nextFloat() * 1.5f);
                canvas.drawCircle(x, y, r, p);
            }
        }

        Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
        int overlay = isDark ? Color.argb(24, 0, 0, 0) : Color.argb(18, 255, 255, 255);
        v.setColor(overlay);
        canvas.drawRect(0, 0, sizePx, sizePx, v);
    }

    private static void drawSoftGradient(Canvas canvas, int sizePx, boolean isDark) {
        int c1 = isDark ? Color.rgb(20, 24, 30) : Color.rgb(250, 252, 255);
        int c2 = isDark ? Color.rgb(30, 40, 50) : Color.rgb(240, 246, 250);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader lg = new LinearGradient(0, 0, sizePx, sizePx, c1, c2, Shader.TileMode.CLAMP);
        p.setShader(lg);
        canvas.drawRect(0, 0, sizePx, sizePx, p);

        p.setShader(null);
        p.setColor(isDark ? Color.argb(18, 255, 255, 255) : Color.argb(12, 0, 0, 0));
        for (int i = 0; i < sizePx / 2; i++) {
            float x = rnd.nextFloat() * sizePx;
            float y = rnd.nextFloat() * sizePx;
            canvas.drawCircle(x, y, rnd.nextFloat() * 1.4f, p);
        }
    }

    private static void drawFiberTexture(Canvas canvas, int sizePx, boolean isDark) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int strokes = sizePx * 8;
        for (int i = 0; i < strokes; i++) {
            int x = rnd.nextInt(sizePx);
            int y = rnd.nextInt(sizePx);
            p.setColor(isDark ? Color.argb(18, 255, 255, 255) : Color.argb(20, 0, 0, 0));
            float len = 8 + rnd.nextFloat() * 16;
            float angle = (float) (rnd.nextFloat() * Math.PI * 2);
            float x2 = x + (float) Math.cos(angle) * len;
            float y2 = y + (float) Math.sin(angle) * len;
            p.setStrokeWidth(1f);
            canvas.drawLine(x, y, x2, y2, p);
        }
    }
}