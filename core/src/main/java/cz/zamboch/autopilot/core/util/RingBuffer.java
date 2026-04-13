package cz.zamboch.autopilot.core.util;

/**
 * Fixed-size circular buffer for lookback history.
 */
public class RingBuffer<T> {
    private final Object[] buffer;
    private int head;
    private int size;

    public RingBuffer(int capacity) {
        this.buffer = new Object[capacity];
        this.head = 0;
        this.size = 0;
    }

    public void add(T item) {
        buffer[head] = item;
        head = (head + 1) % buffer.length;
        if (size < buffer.length) {
            size++;
        }
    }

    /** Get item at index from most recent (0 = newest). */
    @SuppressWarnings("unchecked")
    public T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        int actualIndex = (head - 1 - index + buffer.length) % buffer.length;
        return (T) buffer[actualIndex];
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
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = null;
        }
        head = 0;
        size = 0;
    }
}
