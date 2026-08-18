# V10 Battle Feedback Polish

## What changed
- Battle event cards now animate with scale + fade + shadow and stronger visual hierarchy.
- Rep feedback now enters with a spring, shows PERFECT REP for high-quality reps, and fades out cleanly.
- Combo card now changes language at 5/8/10 combo and gains subtle scale/shadow emphasis.
- Score cards now pulse with a +1 flash when either player scores.
- Added an animated lead bar between the score HUD and camera so the score gap is readable at a glance.
- Kept normal-state HUD quieter; event feedback is visually dominant only when something important happens.
- Existing V9 audio, haptic, WebRTC, matchmaking and rep synchronization were preserved.

## Intended battle feel
Normal rep: small score pulse + short SFX + light haptic.
Perfect rep: stronger score pulse + PERFECT feedback + stronger SFX/haptic.
Overtake: central event animation + overtake SFX + strong haptic.
Opponent scores: opponent card pulse + opponent SFX.
Combo: progressive badge emphasis without permanently covering the camera.
