- Does layer 4 validate wave-break tick ?
- Do Layer 1-4 validate all features ?
- Layer 1-4 for recordings
- ML predict fire (T-1)
- surf their VCS
- ML gun
- https://robowiki.net/wiki/User:Nat/Free_code#Movement_Predictor
- https://robowiki.net/wiki/Maximum_Escape_Angle/Precise
- Gun - during aiming: consider MEA/center vs bot width, bullet width, wave intersection thickness.
- Consider left/right MEA
- Consider max reachable envelope or derived probability bins for sub-arcs
- Handle scan gaps and skipped turns systematically
- "Formation", a timed sequence of moves/bullets/energy/location
   - Recognize opportunity to start "Formation"
   - Recognize "abort" signal -> switch back to default surfing
   - Exploit bounding box 45 degree MEA
   - Exploit walls and corners MEA
   - Exploit dive/escape sequences
   - Use AI to pre-calculate for which of them makes sense to train ML
   - Use DNN/q-learning to find more

Blotter
- run all tests and show me drift by layers and features

run BattleLoopTest without fixed random seed for 35 rounds
- categorize reasons for individual drift instances if any

- we could decide to postpone firing, if the opponent surprized us by his move. For that we would need to make an estimate of his T position at T-1 time. Let's have new MovementPredictorStrategy class that would produce the estimate. 
- we could also postpone fire randomly to see uncertainity for the opponent about our gun heat -> fire time. Note that opponent will learn that we fired in T+1