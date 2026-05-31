- Skipped turn handler
    - I dislike how all accumulators work. Propose larger re-design. Multiple options.
- Fix energy drop detection
    - bullet shield
    - god-view could use the other perspective to validate energy accounting
- Does layer 4 validate wave-break tick ?
- Layer 0 for top 50 - once
- Layer 1-4 for BeepBoop vs BeepBoop
- Layer 1-4 for recordings
- Do Layer 1-4 validate all features ?
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

run all tests without fixed random seed
- show me scores for all robots 
- show me drift by layers and features and opponents. 
- Re-create fresh BeepBoop-fired-bullets.md and BeepBoop-energy-events.md
- Re-create fresh Crazy-fired-bullets.md and Crazy-energy-events.md
- Re-create fresh Agressive-fired-bullets.md and Agressive-energy-events.md
- categorize reasons for individual drift instances