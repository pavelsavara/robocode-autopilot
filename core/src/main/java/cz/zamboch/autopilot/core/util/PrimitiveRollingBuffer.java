package cz.zamboch.autopilot.core.util;

/**
 * Fixed-size circular buffer for primitive doubles with O(1) rolling statistics.
 *
 * <p>Maintains running sum and sum-of-squares for the full buffer and for a
 * configurable "short window" (e.g. 10), enabling O(1) mean and population std
 * without iterating over elements.  Eliminates autoboxing overhead of
 * {@code RingBuffer<Double>}.</p>
 */
public final class PrimitiveRollingBuffer {
    private final double[] buffer;
    private final int capacity;
    private final int shortWindow;
    private int head;
    private int size;

    // Running sums for the full buffer (capacity-sized window)
    private double sumFull;
    private double sumSqFull;

    // Running sums for the short window
    private double sumShort;
    private double sumSqShort;

    /**
     * @param capacity    maximum number of elements (e.g. 30)
     * @param shortWindow smaller window for rolling stats (e.g. 10)
     */
    public PrimitiveRollingBuffer(int capacity, int shortWindow) {
        this.buffer = new double[capacity];
        this.capacity = capacity;
        this.shortWindow = shortWindow;
        this.head = 0;
        this.size = 0;
    }

    public void add(double value) {
        // If buffer is full, the element at head is about to be overwritten
        if (size == capacity) {
            double oldest = buffer[head];
            sumFull -= oldest;
            sumSqFull -= oldest * oldest;
        }

        buffer[head] = value;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }

        // Update full-buffer running sums
        sumFull += value;
        sumSqFull += value * value;

        // Update short-window running sums
        sumShort += value;
        sumSqShort += value * value;
        if (size > shortWindow) {
            // The element that just fell out of the short window
            // is the one at position shortWindow from the newest.
            // Newest is at (head - 1 + capacity) % capacity.
            // Element shortWindow positions back is at (head - 1 - shortWindow + capacity) % capacity.
            int idx = (head - 1 - shortWindow + capacity) % capacity;
            double fallen = buffer[idx];
            sumShort -= fallen;
            sumSqShort -= fallen * fallen;
        }
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    /** O(1) mean over the last {@code min(size, window)} elements. */
    public double mean(int window) {
        int n = Math.min(size, window);
        if (n == 0) return 0.0;
        double s = (window <= shortWindow) ? sumShort : sumFull;
        return s / n;
    }

    /** O(1) population standard deviation over the last {@code min(size, window)} elements. */
    public double std(int window) {
        int n = Math.min(size, window);
        if (n < 2) return 0.0;
        double s, sq;
        if (window <= shortWindow) {
            s = sumShort;
            sq = sumSqShort;
        } else {
            s = sumFull;
            sq = sumSqFull;
        }
        double mean = s / n;
        double variance = sq / n - mean * mean;
        // Guard against tiny negative from floating point
        return variance > 0 ? Math.sqrt(variance) : 0.0;
    }

    public void clear() {
        for (int i = 0; i < capacity; i++) {
            buffer[i] = 0.0;
        }
        head = 0;
        size = 0;
        sumFull = 0;
        sumSqFull = 0;
        sumShort = 0;
        sumSqShort = 0;
    }
}
