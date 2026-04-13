package cz.zamboch.autopilot.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature processor orchestrator. Registers processors, resolves dependency order once,
 * then executes all processors in sorted order each tick.
 */
public class Transformer {
    private final List<IFeatureProcessor> registered = new ArrayList<IFeatureProcessor>();
    private List<IFeatureProcessor> sortedProcessors;

    public void register(IFeatureProcessor processor) {
        registered.add(processor);
    }

    /**
     * Resolve dependency order via topological sort. Called once at battle start.
     * @throws IllegalStateException if circular dependencies detected
     */
    public void resolveDependencies() {
        // Build adjacency: feature -> processor that produces it
        Map<Feature, IFeatureProcessor> producers = new HashMap<Feature, IFeatureProcessor>();
        for (IFeatureProcessor p : registered) {
            for (Feature f : p.getOutputFeatures()) {
                producers.put(f, p);
            }
        }

        // Topological sort using Kahn's algorithm
        Map<IFeatureProcessor, Integer> inDegree = new HashMap<IFeatureProcessor, Integer>();
        Map<IFeatureProcessor, List<IFeatureProcessor>> adj = new HashMap<IFeatureProcessor, List<IFeatureProcessor>>();

        for (IFeatureProcessor p : registered) {
            inDegree.put(p, 0);
            adj.put(p, new ArrayList<IFeatureProcessor>());
        }

        for (IFeatureProcessor p : registered) {
            for (Feature dep : p.getDependencies()) {
                IFeatureProcessor depProducer = producers.get(dep);
                if (depProducer != null && depProducer != p) {
                    adj.get(depProducer).add(p);
                    inDegree.put(p, inDegree.get(p) + 1);
                }
            }
        }

        List<IFeatureProcessor> sorted = new ArrayList<IFeatureProcessor>();
        List<IFeatureProcessor> queue = new ArrayList<IFeatureProcessor>();
        for (Map.Entry<IFeatureProcessor, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            IFeatureProcessor current = queue.remove(0);
            sorted.add(current);
            for (IFeatureProcessor neighbor : adj.get(current)) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (sorted.size() != registered.size()) {
            throw new IllegalStateException("Circular dependency detected among feature processors");
        }

        this.sortedProcessors = sorted;
    }

    /** Execute all processors in pre-sorted dependency order. */
    public void process(Whiteboard wb) {
        if (sortedProcessors == null) {
            throw new IllegalStateException("resolveDependencies() must be called before process()");
        }
        for (IFeatureProcessor p : sortedProcessors) {
            p.process(wb);
        }
    }

    /** Get processors for a specific file type (used by CsvWriter). */
    public List<IFeatureProcessor> getProcessors(FileType fileType) {
        List<IFeatureProcessor> result = new ArrayList<IFeatureProcessor>();
        List<IFeatureProcessor> source = sortedProcessors != null ? sortedProcessors : registered;
        for (IFeatureProcessor p : source) {
            if (p.getFileType() == fileType) {
                result.add(p);
            }
        }
        return result;
    }
}
