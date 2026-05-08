package cz.zamboch.trivial;

/**
 * Opponent strength lookup table from LiteRumble top-50 APS rankings.
 *
 * <p>Data: 50 entries, sorted by FNV-1a hash of the fully-qualified class name
 * (the part before the first space in Robocode's {@code getName()}).
 * Each entry is 8 bytes: {@code [hash:int32, strength_x1000:int32]} big-endian,
 * Base64-encoded. Strength = APS / 100 (e.g. 95.18 APS → 0.952).</p>
 *
 * <p>Source: https://literumble.appspot.com/Rankings?game=roborumble&amp;limit=50
 * (2026-05-08)</p>
 */
public final class OpponentProfileData {

    private OpponentProfileData() {}

    /** 50 entries × 8 bytes = 400 bytes, Base64 = 536 chars. */
    private static final String DATA_B64 =
            "hwRyLwAAA3ONN+oVAAADPKIByqsAAAM+p2MbDgAAAzmsgJg7AAADSLLAqUsAAANkvQmSfwAAA2XC"
          + "mvcPAAADU8lZ7GcAAANRyx1U+wAAA2/MxXBTAAADZM40nfEAAANA1MrnrwAAA2/Vz1wsAAADV93X"
          + "OB8AAANL4S2tpwAAAz/hk3+wAAADPeGrQ8sAAANN5e/FKwAAAzrp1lGrAAADRvIKzjkAAANW+rWg"
          + "XAAADUP+rSoZAAADQAGCyqMAAANCBVBI7gAAA2QGG7hTAAADQwbDApQAAANuDAF3DAAAA5ENL9BO"
          + "AAADkhYvmMAAAANkI42vjQAAA0E+kY+CAAADOj9BnaAAAANiP7ouaQAAA7hAK9X6AAADOUdh1h4A"
          + "AANKVDVK0QAAA0ZX7e80AAADOljXRnAAAAOTW4BwGQAAAztcqoRmAAADb1719O0AAANQYEyRdAAA"
          + "DtkSMmsAAADQm8sXuEAAAOHb5nQkgAAA1Fw98A2AAADP3pRL+wAAANQfY/WjwAAA1x/6JjlAAADcA==";

    private static final int ENTRY_SIZE = 8;
    private static final double DEFAULT_STRENGTH = 0.5;

    /** Lazily decoded table: [hash0, str0, hash1, str1, ...]. */
    private static int[] table;

    /**
     * Get the overall strength rating for an opponent.
     *
     * @param botIdHash FNV-1a hash of the opponent's bot ID (fully-qualified class name)
     * @return strength in [0,1]: 0 = weakest, 1 = strongest, 0.5 = unknown
     */
    public static double getStrengthRating(int botIdHash) {
        int[] t = getTable();
        int idx = binarySearch(t, botIdHash);
        if (idx < 0) {
            return DEFAULT_STRENGTH;
        }
        return t[idx + 1] / 1000.0;
    }

    private static int[] getTable() {
        if (table == null) {
            table = decode(DATA_B64);
        }
        return table;
    }

    /** Binary search for hash in flat [hash, value, hash, value, ...] array. */
    private static int binarySearch(int[] t, int key) {
        int lo = 0;
        int hi = t.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midHash = t[mid * 2];
            if (midHash < key) {
                lo = mid + 1;
            } else if (midHash > key) {
                hi = mid - 1;
            } else {
                return mid * 2;
            }
        }
        return -1;
    }

    /** Decode Base64 → big-endian int pairs. */
    private static int[] decode(String b64) {
        byte[] raw = java.util.Base64.getDecoder().decode(b64);
        int n = raw.length / ENTRY_SIZE;
        int[] result = new int[n * 2];
        for (int i = 0; i < n; i++) {
            int off = i * ENTRY_SIZE;
            result[i * 2] = ((raw[off] & 0xFF) << 24)
                          | ((raw[off + 1] & 0xFF) << 16)
                          | ((raw[off + 2] & 0xFF) << 8)
                          | (raw[off + 3] & 0xFF);
            result[i * 2 + 1] = ((raw[off + 4] & 0xFF) << 24)
                               | ((raw[off + 5] & 0xFF) << 16)
                               | ((raw[off + 6] & 0xFF) << 8)
                               | (raw[off + 7] & 0xFF);
        }
        return result;
    }
}
