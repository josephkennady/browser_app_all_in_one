package com.kododake.aabrowser.tv;
import org.junit.Test;
import static org.junit.Assert.*;

public class TvControlsTest {
    @Test public void pointerClampsAndScrollsOnlyAtVerticalEdges() {
        RemotePointer p = new RemotePointer(); p.center(100, 100);
        assertEquals(0, p.move(1, 0, 90, 100, 100));
        assertEquals(99, p.x, 0);
        assertEquals(20, p.move(0, 1, 20, 100, 60));
        assertEquals(59, p.y, 0);
        assertEquals(-100, p.move(0, -1, 100, 100, 60));
        assertEquals(0, p.y, 0);
        p.move(-1, 0, 200, 100, 60); assertEquals(0, p.x, 0);
    }
    @Test public void pointerHandlesUnmeasuredViewport() {
        RemotePointer p = new RemotePointer(); p.center(0, 0);
        p.move(1, 1, 10, 0, 0);
        assertEquals(0, p.x, 0); assertEquals(0, p.y, 0);
    }
    @Test public void addressesAndSearchesAreResolvedWithoutExecutableSchemes() {
        assertEquals("https://youtube.com", BrowserAddress.resolve(" youtube.com "));
        assertEquals("https://m.youtube.com/watch?v=123", BrowserAddress.resolve("https://m.youtube.com/watch?v=123"));
        assertEquals("https://www.google.com/search?q=music+videos", BrowserAddress.resolve("music videos"));
        assertTrue(BrowserAddress.resolve("javascript:alert(1)").startsWith("https://www.google.com/search?q="));
        assertNull(BrowserAddress.resolve("  "));
    }
}
