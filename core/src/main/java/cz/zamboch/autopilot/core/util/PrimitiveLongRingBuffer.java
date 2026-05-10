package cz.zamboch.autopilot.core.util;

/**
 * Fixed-size circular buffer for primitive {@code long} values.
 * Avoids autoboxing overhead of {@link RingBuffer}{@code <Long>}.
 */
public final class PrimitiveLongRingBuffer {
    private final long[] buffer;
    private int head;
    private int size;

    public PrimitiveLongRingBuffer(int capacity) {
        this.buffer = new long[capacity];
        this.head = 0;
        this.size = 0;
    }

    public void add(long value) {
        buffer[head] = value;
        head = (head + 1) % buffer.length;
        if (size < buffer.length) {
            size++;
        }
    }

    /** Get item at index from most recent (0 = newest). */
    public long get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        int actualIndex = (head - 1 - index + buffer.length) % buffer.length;
        return buffer[actualIndex];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return buffer.length;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        head = 0;
        size = 0;
    }
}
