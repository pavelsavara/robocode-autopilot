package cz.zamboch.autopilot.pipeline;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI entry point for the Stage 2 pipeline.
 * Processes .br recordings into CSV feature files.
 */
public final class Main {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: pipeline --input <dir> --output <dir> [--features all|core]");
            System.exit(1);
        }

        Path inputDir = null;
        Path outputDir = null;

        for (int i = 0; i < args.length - 1; i++) {
            if ("--input".equals(args[i])) {
                inputDir = Paths.get(args[i + 1]);
            } else if ("--output".equals(args[i])) {
                outputDir = Paths.get(args[i + 1]);
            }
        }

        if (inputDir == null || outputDir == null) {
            System.err.println("Both --input and --output are required");
            System.exit(1);
        }

        System.out.println("Robocode Autopilot Pipeline");
        System.out.println("Input:  " + inputDir);
        System.out.println("Output: " + outputDir);
        // TODO: Phase B+ — scan for .br files, process each
    }
}
