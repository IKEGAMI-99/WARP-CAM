package com.ikegami99.warpcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

public final class TrackStripRenderer {
    private TrackStripRenderer() {}

    public static Bitmap append(Bitmap photo, NowPlayingInfo track) {
        if (photo == null || track == null || !track.hasTrack()) return photo;

        int w = photo.getWidth();
        int h = photo.getHeight();
        int stripH = Math.max(160, Math.round(h * 0.15f));
        int margin = Math.max(24, Math.round(stripH * 0.12f));
        int artSize = Math.max(1, stripH - margin * 2);

        Bitmap out = Bitmap.createBitmap(w, h + stripH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(photo, 0, 0, null);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xff090909);
        canvas.drawRect(0, h, w, h + stripH, bg);

        Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        divider.setColor(0xff2a2a2a);
        divider.setStrokeWidth(Math.max(2f, w / 900f));
        canvas.drawLine(0, h, w, h, divider);

        int artLeft = margin;
        int artTop = h + margin;
        if (track.artwork != null && !track.artwork.isRecycled()) {
            drawCenterCrop(canvas, track.artwork,
                    new RectF(artLeft, artTop, artLeft + artSize, artTop + artSize));
        } else {
            Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
            placeholder.setColor(0xff202020);
            canvas.drawRect(artLeft, artTop, artLeft + artSize, artTop + artSize, placeholder);
        }

        float textLeft = artLeft + artSize + margin;
        float textRight = w - margin;
        float textWidth = Math.max(1f, textRight - textLeft);

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(0xff8f8f8f);
        label.setTextSize(stripH * 0.095f);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("NOW PLAYING", textLeft, h + stripH * 0.25f, label);

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(0xffffffff);
        title.setTextSize(stripH * 0.235f);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        String titleText = ellipsize(track.title.isEmpty() ? "Unknown track" : track.title, title, textWidth);
        canvas.drawText(titleText, textLeft, h + stripH * 0.55f, title);

        Paint artist = new Paint(Paint.ANTI_ALIAS_FLAG);
        artist.setColor(0xffb8b8b8);
        artist.setTextSize(stripH * 0.155f);
        String artistText = ellipsize(track.artist.isEmpty() ? track.album : track.artist, artist, textWidth);
        canvas.drawText(artistText, textLeft, h + stripH * 0.79f, artist);

        Paint footer = new Paint(Paint.ANTI_ALIAS_FLAG);
        footer.setColor(0xff666666);
        footer.setTextSize(stripH * 0.085f);
        String footerText = track.packageName.isEmpty() ? "WARP CAM" : "WARP CAM  •  " + track.packageName;
        canvas.drawText(ellipsize(footerText, footer, textWidth), textLeft, h + stripH * 0.94f, footer);

        return out;
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF dst) {
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        int side = Math.min(bw, bh);
        int left = (bw - side) / 2;
        int top = (bh - side) / 2;
        Rect src = new Rect(left, top, left + side, top + side);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bitmap, src, dst, p);
    }

    private static String ellipsize(String text, Paint paint, float maxWidth) {
        if (text == null) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "…";
        float e = paint.measureText(ellipsis);
        int end = text.length();
        while (end > 0 && paint.measureText(text, 0, end) + e > maxWidth) end--;
        return end <= 0 ? ellipsis : text.substring(0, end).trim() + ellipsis;
    }
}
