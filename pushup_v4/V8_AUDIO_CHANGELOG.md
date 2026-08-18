# V8 — Battle Audio Polish

- Replaced battle SFX with a consistent synthesized arcade/fitness palette.
- Added a dedicated `opponent_rep.wav` cue and wired it to remote REP updates.
- Added cooldowns and stream limits in `BattleAudioManager` to reduce audio clutter.
- Existing battle events remain mapped: countdown, FIGHT, rep, perfect rep, combo, overtake, warning, match point, victory, defeat, draw, disconnect.

The sounds are short, mono WAV assets designed for `SoundPool` and `USAGE_GAME`.
