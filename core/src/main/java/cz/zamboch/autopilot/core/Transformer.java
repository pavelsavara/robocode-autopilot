package cz.zamboch.autopilot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Feature orchestrator. Registers IInGameFeatures implementations,
 * topological-sorts them by declared dependencies (Kahn's algorithm),
 * and executes them in dependency order each tick.
 *
 * Deterministic: ties broken by class name.
 */
public final class Transformer {
    private final List<IInGameFeatures> registered = new ArrayList<IInGameFeatures>();
    private IInGameFeatures[] executionOrder;

    public void register(IInGameFeatures features) {
        registered.add(features);
        executionOrder = null; // invalidate
    }

    /** Resolve execution order. Call after all features are registered. */
    public void resolveDependencies() {
        int n = registered.size();
        // Sort by class name for deterministic tie-breaking
        List<IInGameFeatures> sorted = new ArrayList<IInGameFeatures>(registered);
        sorted.sort(Comparator.comparing(f -> f.getClass().getName()));

        // Map: Feature -> index of producer in sorted list
        Map<Feature, Integer> producerIndex = new HashMap<Feature, Integer>();
        for (int i = 0; i < sorted.size(); i++) {
            for (Feature f : sorted.get(i).getOutputFeatures()) {
                producerIndex.put(f, i);
            }
        }

        // Build adjacency + in-degree for topological sort
        int[] inDegree = new int[n];
        List<List<Integer>> adj = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<Integer>());
        }

        for (int i = 0; i < n; i++) {
            for (Feature dep : sorted.get(i).getDependencies()) {
                Integer producer = producerIndex.get(dep);
                if (producer != null && producer != i) {
                    adj.get(producer).add(i);
                    inDegree[i]++;
                }
                // If producer is null, it's a raw input (set by robot directly) — no edge
                // needed
            }
        }

        // Kahn's algorithm
        Queue<Integer> queue = new LinkedList<Integer>();
        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0) {
                queue.add(i);
            }
        }

        List<IInGameFeatures> result = new ArrayList<IInGameFeatures>();
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            result.add(sorted.get(idx));
            for (int neighbor : adj.get(idx)) {
                inDegree[neighbor]--;
                if (inDegree[neighbor] == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (result.size() != n) {
            throw new IllegalStateException("Cyclic dependency detected in feature graph");
        }

        executionOrder = result.toArray(new IInGameFeatures[0]);
    }

    /** Execute all features in dependency order for the current tick. */
    public void process(Whiteboard wb) {
        if (executionOrder == null) {
            resolveDependencies();
        }
        for (IInGameFeatures f : executionOrder) {
            f.process(wb);
        }
    }

    /** Get the resolved execution order (for debugging/testing). */
    public IInGameFeatures[] getExecutionOrder() {
        if (executionOrder == null) {
            resolveDependencies();
        }
        return Arrays.copyOf(executionOrder, executionOrder.length);
    }
}
