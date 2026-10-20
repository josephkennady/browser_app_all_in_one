package com.kododake.aabrowser.tv;

/** Pixel coordinates relative to the page viewport, independent of page scrolling. */
final class RemotePointer {
    float x, y;
    void center(int width, int height) { x = width / 2f; y = height / 2f; }
    int move(int dx, int dy, float step, int width, int height) {
        float nextY = y + dy * step;
        x = Math.max(0, Math.min(Math.max(0, width - 1), x + dx * step));
        y = Math.max(0, Math.min(Math.max(0, height - 1), nextY));
        return nextY < 0 ? -(int) step : nextY >= height ? (int) step : 0;
    }
}
