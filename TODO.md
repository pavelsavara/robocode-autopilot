
Usage pattern (for future PathPlanner)

```java
// Allocate once at battle start (on Whiteboard or PathPlanner)
CandidatePosition[] buf = new CandidatePosition[64];
for (int i = 0; i < 64; i++) buf[i] = new CandidatePosition();
MutableRobotState simState = new MutableRobotState();
double[] gfBuf = new double[61];

// Per tick — zero allocation
int n = ReachableEnvelope.getCandidatesInto(state, bfW, bfH, buf, 30);
for (int i = 0; i < n; i++) {
    PrecisePredictor.simulate(state, accel, turn, 10, bfW, bfH, simState);
    predictor.predictInto(wb, gfBuf);
    // score buf[i] using simState and gfBuf
}
```

Also add them into CSV files