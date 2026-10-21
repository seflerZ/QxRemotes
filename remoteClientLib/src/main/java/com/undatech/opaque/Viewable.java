package com.undatech.opaque;

import android.graphics.Bitmap;

public interface Viewable {
    void waitUntilInflated();

    int getDesiredWidth();

    int getDesiredHeight();

    void reallocateDrawable(int width, int height);

    Bitmap getBitmap();

    /**
     * The bitmap a native renderer should write the next frame into. For
     * protocols that use double buffering this is the back buffer, so the
     * visible frame is never touched while it is being drawn.
     */
    Bitmap getWriteBitmap();

    /**
     * Publish a frame that was written into {@link #getWriteBitmap()}. No-op
     * for protocols that draw straight into the visible bitmap.
     */
    void publishFrame();

    void reDraw(int x, int y, int width, int height);

    void reDraw(DrawTask task);

    void setMousePointerPosition(int x, int y);

    void setSoftCursorPixels(int[] pixels, int width, int height, int xPos, int yPos);

    void setSoftCursorBitmap(Bitmap bitmap, int width, int height, int xPos, int yPos);

    void mouseMode(boolean relative);

    boolean isAbleToPan();

    void onConnectionSuccess();

    // 更新连接进度对话框上的中间状态文案(如 moonlight 的各个 stage)
    void setConnectionStatus(String status);

    // 关闭连接进度对话框(连接成功或失败/终止时)
    void dismissConnectionProgress();
}
