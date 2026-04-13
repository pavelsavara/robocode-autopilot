package cz.zamboch.autopilot.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature orchestrator. Registers features, resolves dependency order once,
 * then executes all features in sorted order each tick.
 */
public final class Transformer {
    private final List<IInGameFeatures> registered = new ArrayList<IInGameFeatures>();
    private List<IInGameFeatures> sortedFeatures;

    public void register(IInGameFeatures feature) {
        registered.add(feature);
    }

    /**
     * Resolve dependency order via topological sort. Called once at battle start.
     * @throws IllegalStateException if circular dependencies detected
     */
    public void resolveDependencies() {
        // Build adjacency: feature enum -> feature impl that produces it
        Map<Feature, IInGameFeatures> producers = new HashMap<Feature, IInGameFeatures>();
        for (IInGameFeatures p : registered) {
            for (Feature f : p.getOutputFeatures()) {
                producers.put(f, p);
            }
        }

        // Topological sort using Kahn's algorithm
        Map<IInGameFeatures, Integer> inDegree = new HashMap<IInGameFeatures, Integer>();
        Map<IInGameFeatures, List<IInGameFeatures>> adj = new HashMap<IInGameFeatures, List<IInGameFeatures>>();

        for (IInGameFeatures p : registered) {
            inDegree.put(p, 0);
            adj.put(p, new ArrayList<IInGameFeatures>());
        }

        for (IInGameFeatures p : registered) {
            for (Feature dep : p.getDependencies()) {
                IInGameFeatures depProducer = producers.get(dep);
                if (depProducer != null && depProducer != p) {
                    adj.get(depProducer).add(p);
                    inDegree.put(p, inDegree.get(p) + 1);
                }
            }
        }

        List<IInGameFeatures> sorted = new ArrayList<IInGameFeatures>();
        List<IInGameFeatures> queue = new ArrayList<IInGameFeatures>();
        for (Map.Entry<IInGameFeatures, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            IInGameFeatures current = queue.remove(0);
            sorted.add(current);
            for (IInGameFeatures neighbor : adj.get(current)) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (sorted.size() != registered.size()) {
            throw new IllegalStateException("Circular dependency detected among features");
        }

        this.sortedFeatures = sorted;
    }

    /** Execute all features in pre-sorted dependency order. */
    public void process(Whiteboard wb) {
        if (sortedFeatures == null) {
            throw new IllegalStateException("resolveDependencies() must be called before process()");
        }
        for (IInGameFeatures p : sortedFeatures) {
            p.process(wb);
        }
    }

    /** Get all registered features (in dependency order if resolved). */
    public List<IInGameFeatures> getFeatures() {
        return sortedFeatures != null ? sortedFeatures : registered;
    }
}
