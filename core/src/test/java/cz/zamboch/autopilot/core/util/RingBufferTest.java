package cz.zamboch.autopilot.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RingBuffer: add, get, overflow, clear, edge cases.
 */
class RingBufferTest {

    @Test
    void emptyBufferHasSizeZero() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(5);
        assertEquals(0, buf.size());
        assertTrue(buf.isEmpty());
        assertEquals(5, buf.capacity());
    }

    @Test
    void addAndGetSingleItem() {
        RingBuffer<String> buf = new RingBuffer<String>(3);
        buf.add("hello");
        assertEquals(1, buf.size());
        assertFalse(buf.isEmpty());
        assertEquals("hello", buf.get(0)); // most recent
    }

    @Test
    void getReturnsNewestFirst() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(5);
        buf.add(10);
        buf.add(20);
        buf.add(30);
        assertEquals(3, buf.size());
        assertEquals(30, buf.get(0)); // newest
        assertEquals(20, buf.get(1));
        assertEquals(10, buf.get(2)); // oldest
    }

    @Test
    void overflowEvictsOldest() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(3);
        buf.add(1);
        buf.add(2);
        buf.add(3);
        buf.add(4); // evicts 1
        assertEquals(3, buf.size());
        assertEquals(4, buf.get(0));
        assertEquals(3, buf.get(1));
        assertEquals(2, buf.get(2));
    }

    @Test
    void clearResetsState() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(3);
        buf.add(1);
        buf.add(2);
        buf.clear();
        assertEquals(0, buf.size());
        assertTrue(buf.isEmpty());
    }

    @Test
    void getThrowsOnInvalidIndex() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(3);
        buf.add(10);

        assertThrows(IndexOutOfBoundsException.class, () -> buf.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> buf.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> buf.get(5));
    }

    @Test
    void getThrowsOnEmptyBuffer() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(3);
        assertThrows(IndexOutOfBoundsException.class, () -> buf.get(0));
    }

    @Test
    void wrapsAroundMultipleTimes() {
        RingBuffer<Integer> buf = new RingBuffer<Integer>(2);
        for (int i = 0; i < 10; i++) {
            buf.add(i);
        }
        assertEquals(2, buf.size());
        assertEquals(9, buf.get(0)); // newest
        assertEquals(8, buf.get(1));
    }
}
