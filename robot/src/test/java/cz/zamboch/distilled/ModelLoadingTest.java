package cz.zamboch.distilled;

import cz.zamboch.autopilot.core.ml.GbmTreeEnsemble;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ModelLoadingTest {

    @Test void firePowerModelLoads() {
        GbmTreeEnsemble m = FirePowerData.load();
        assertNotNull(m); assertEquals(FirePowerData.N_TREES, m.getNumTrees());
        double[] in = new double[FirePowerData.FEATURE_NAMES.length];
        assertTrue(Double.isFinite(m.predictRaw(in)));
    }

    @Test void fireTimingModelLoads() {
        GbmTreeEnsemble m = FireTimingData.load();
        assertNotNull(m); assertEquals(FireTimingData.N_TREES, m.getNumTrees());
        double[] in = new double[FireTimingData.FEATURE_NAMES.length];
        double p = GbmTreeEnsemble.sigmoid(m.predictRaw(in));
        assertTrue(p >= 0 && p <= 1);
    }

    @Test void movementModelLoads() {
        GbmTreeEnsemble m = MovementData.load();
        assertNotNull(m); assertEquals(MovementData.N_TREES, m.getNumTrees());
        assertTrue(Double.isFinite(m.predictRaw(new double[MovementData.FEATURE_NAMES.length])));
    }

    @Test void featureNamesMappable() {
        assertAllMapped(FirePowerData.FEATURE_NAMES, "FP");
        assertAllMapped(FireTimingData.FEATURE_NAMES, "FT");
        assertAllMapped(MovementData.FEATURE_NAMES, "MV");
    }

    private void assertAllMapped(String[] names, String label) {
        cz.zamboch.autopilot.core.Feature[] idx =
            cz.zamboch.autopilot.core.ml.FeatureMapping.buildIndex(names);
        for (int i = 0; i < idx.length; i++)
            assertNotNull(idx[i], label + " feature '" + names[i] + "' unmapped at " + i);
    }

    @Test void firePowerMatchesPythonFixtures() throws Exception {
        verifyFixtures(FirePowerData.load(), "fire_power", FirePowerData.FEATURE_NAMES.length, 0.001);
    }
    @Test void fireTimingMatchesPythonFixtures() throws Exception {
        verifyFixtures(FireTimingData.load(), "fire_timing", FireTimingData.FEATURE_NAMES.length, 0.001);
    }
    @Test void movementMatchesPythonFixtures() throws Exception {
        verifyFixtures(MovementData.load(), "movement", MovementData.FEATURE_NAMES.length, 0.001);
    }

    @Test void featureDimensionValidation() {
        FirePowerData.load().validateFeatureDimension(FirePowerData.FEATURE_NAMES.length, "FP");
        FireTimingData.load().validateFeatureDimension(FireTimingData.FEATURE_NAMES.length, "FT");
        MovementData.load().validateFeatureDimension(MovementData.FEATURE_NAMES.length, "MV");
    }

    private void verifyFixtures(GbmTreeEnsemble model, String task, int nf, double tol) throws Exception {
        assertNotNull(model);
        InputStream is = getClass().getResourceAsStream("/distilled/" + task + "_fixtures.json");
        if (is == null) { fail("Missing fixture for " + task); return; }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        double[][] inputs = extract2D(json, "inputs");
        double[][] expected = extract2D(json, "expected_raw");
        assertTrue(inputs.length > 0); assertEquals(inputs.length, expected.length);
        int bad = 0; double maxErr = 0;
        for (int s = 0; s < inputs.length; s++) {
            assertEquals(nf, inputs[s].length);
            double err = Math.abs(model.predictRaw(inputs[s]) - expected[s][0]);
            maxErr = Math.max(maxErr, err);
            if (err > tol) { bad++; if (bad <= 3) System.err.println(task + "[" + s + "] err=" + err); }
        }
        assertEquals(0, bad, task + ": " + bad + "/" + inputs.length + " predictions differ (maxErr=" + maxErr + "). FEATURE_NAMES order mismatch?");
    }

    private static double[][] extract2D(String json, String key) {
        int s = json.indexOf("\"" + key + "\""); s = json.indexOf("[[", s);
        int e = mb(json, s) + 1; return p2d(json.substring(s, e));
    }
    private static int mb(String s, int p) {
        int d = 0;
        for (int i = p; i < s.length(); i++) { if (s.charAt(i)=='[') d++; else if (s.charAt(i)==']') { d--; if (d==0) return i; } }
        return s.length()-1;
    }
    private static double[][] p2d(String j) {
        j = j.trim(); if (j.startsWith("[")) j = j.substring(1); if (j.endsWith("]")) j = j.substring(0, j.length()-1);
        java.util.List<double[]> rows = new java.util.ArrayList<>(); int i = 0;
        while (i < j.length()) { int a = j.indexOf('[', i); if (a<0) break; int b = j.indexOf(']', a); if (b<0) break;
            String[] p = j.substring(a+1, b).split(","); double[] r = new double[p.length];
            for (int k=0; k<p.length; k++) r[k] = Double.parseDouble(p[k].trim()); rows.add(r); i = b+1; }
        return rows.toArray(new double[0][]);
    }
}
