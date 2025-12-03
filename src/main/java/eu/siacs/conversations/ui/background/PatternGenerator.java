package eu.siacs.conversations.ui.background;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RadialGradient;
import android.graphics.Shader;

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

    public static Bitmap generate(Context ctx, PatternType type, int size, boolean dark) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        switch (type) {
            case SOFT_MESH:
                int smA = dark ? 0xFF2B2B2B : 0xFFF3F6F9;
                int smB = dark ? 0xFF1C1C1C : 0xFFE8EEF6;
                LinearGradient lg = new LinearGradient(0, 0, size, size, smA, smB, Shader.TileMode.MIRROR);
                p.setShader(lg);
                c.drawRect(0, 0, size, size, p);
                break;

            case GRAINY_PAPER:
                c.drawColor(dark ? 0xFF171717 : 0xFFF8F8F8);
                p.setStyle(Style.FILL);
                p.setColor(dark ? 0x22FFFFFF : 0x22000000);
                for (int i = 0; i < size * 6; i++) {
                    float x = (float) (Math.random() * size);
                    float y = (float) (Math.random() * size);
                    c.drawCircle(x, y, (float) (Math.random() * 1.5f), p);
                }
                break;

            case DOTTED_NOISE:
                c.drawColor(dark ? 0xFF222222 : 0xFFF7F7F7);
                p.setStyle(Style.FILL);
                p.setColor(dark ? 0x33333333 : 0x33000000);
                float step = Math.max(8f, size / 22f);
                for (float y = 0; y < size; y += step) {
                    for (float x = 0; x < size; x += step) {
                        c.drawCircle(x + (step / 4f), y + (step / 4f), step / 8f, p);
                    }
                }
                break;

            case DIAMOND_GRID:
                c.drawColor(dark ? 0xFF1E1E1E : 0xFFF9F9F9);
                p.setStyle(Style.STROKE);
                p.setStrokeWidth(Math.max(1f, size / 220f));
                p.setColor(dark ? 0x22323232 : 0x22CCCCCC);
                int stepGrid = Math.max(24, size / 12);
                for (int i = -size; i < size * 2; i += stepGrid) {
                    c.drawLine(i, 0, i + size, size, p);
                    c.drawLine(i, size, i + size, 0, p);
                }
                break;

            case FLOATING_SPHERES:
                c.drawColor(dark ? 0xFF101010 : 0xFFF2F6F9);
                for (int i = 0; i < 40; i++) {
                    float cx = (float) (Math.random() * size);
                    float cy = (float) (Math.random() * size);
                    float r = (float) (size * (0.03 + Math.random() * 0.12));
                    int col = dark ? 0x33FFFFFF : 0x22000000;
                    RadialGradient rg = new RadialGradient(cx, cy, r, col, Color.TRANSPARENT, Shader.TileMode.CLAMP);
                    p.setShader(rg);
                    c.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                }
                break;

            case PERLIN_LIKE_NOISE:
                int a = dark ? 0xFF141414 : 0xFFF6F7F9;
                int b = dark ? 0xFF0C0C0C : 0xFFECEFF3;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        double v = Math.sin(x * 0.03) + Math.cos(y * 0.02);
                        float t = (float) ((v + 2.0) / 4.0);
                        int rr = (int) (Color.red(a) * (1 - t) + Color.red(b) * t);
                        int gg = (int) (Color.green(a) * (1 - t) + Color.green(b) * t);
                        int bb = (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t);
                        bmp.setPixel(x, y, Color.rgb(rr, gg, bb));
                    }
                }
                break;

            case SOFT_GRADIENT:
                int s1 = dark ? 0xFF2C2C2C : 0xFFFFFFFF;
                int s2 = dark ? 0xFF151515 : 0xFFDDE8F2;
                Shader sh = new LinearGradient(0, 0, size, size, s1, s2, Shader.TileMode.CLAMP);
                p.setShader(sh);
                c.drawRect(0, 0, size, size, p);
                break;

            case SCRIBBLE_PRO:
                c.drawColor(dark ? 0xFF1B1B1B : 0xFFF8FBFD);
                p.setStyle(Style.STROKE);
                p.setStrokeWidth(Math.max(1f, size / 200f));
                p.setColor(dark ? 0x22777777 : 0x22000000);
                for (int i = 0; i < 60; i++) {
                    float x1 = (float) (Math.random() * size);
                    float y1 = (float) (Math.random() * size);
                    float x2 = x1 + (float) (Math.random() * size / 6 - size / 12);
                    float y2 = y1 + (float) (Math.random() * size / 6 - size / 12);
                    c.drawLine(x1, y1, x2, y2, p);
                }
                break;

            case FIBER_TEXTURE:
                c.drawColor(dark ? 0xFF111111 : 0xFFF7F8F9);
                p.setStyle(Style.STROKE);
                p.setStrokeWidth(1f);
                p.setColor(dark ? 0x22333333 : 0x22000000);
                for (int i = 0; i < size / 2; i++) {
                    float x = (float) (Math.random() * size);
                    float y = (float) (Math.random() * size);
                    float l = (float) (4 + Math.random() * 40);
                    float aAng = (float) (Math.random() * Math.PI * 2);
                    c.drawLine(x, y, x + (float) Math.cos(aAng) * l, y + (float) Math.sin(aAng) * l, p);
                }
                break;

            default:
                c.drawColor(dark ? 0xFF1E1E1E : 0xFFF4F4F4);
                break;
        }

        if (dark) {
            Paint overlay = new Paint();
            overlay.setColor(Color.argb(60, 0, 0, 0));
            c.drawRect(0, 0, size, size, overlay);
        }

        return bmp;
    }
}