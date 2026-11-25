# Haunt

`/haunt <player>` - haunt someone with fake players and jumpscares. **Only the haunted player sees or hears any of it.**

## Commands
- `/haunt <player> [duration] [intensity 1-3]` - e.g. `/haunt Steve 5m 3` (duration: `90`, `90s`, `5m`, `1h`)
- `/haunt scare <player> <stalker|behind|rush|crowd|whisper|sound|jumpscare>` - trigger one scare right now
- `/haunt stop <player|all>`, `/haunt list`, `/haunt reload`
- `/haunt optout` - a player turns haunting off for themselves (toggle)

Permissions: `haunt.use` (ops), `haunt.bypass` (cannot be haunted), `haunt.optout` (everyone).

## What it does
- **Fake players** are Mannequin entities (1.21.9+), with the haunted player's own skin, another online player's, or one from `names`.
  On older servers they fall back to posed armor stands with a player head.
- **Stalker**: stands far away staring; vanishes if you look at it. **Behind**: appears right behind you; turn around and it's in your face.
  **Rush**: sprints at you and screams. **Crowd**: a ring of them closes in. **Whisper**: fake chat/join messages. **Sound**: footsteps, doors, chests.
- Warden heartbeat that speeds up as they get close, plus night and rain for them only.
- Hitting a ghost makes it vanish. Everything is removed when the haunt ends, the player dies, leaves, or you `/haunt stop`.

## Build
`mvn -B package` (Java 21) or push to GitHub and download the **Haunt** artifact. Needs only Paper. Works on Folia.
