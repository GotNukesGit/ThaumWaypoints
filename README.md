# Vis Waypoints

A client-side helper for GregTech: New Horizons that routes you to the Thaumcraft aura nodes
that can refill your wand, and cleans up after itself when the wand is full.

Press a key with a wand in hand and it works out which primals you're short of, picks the
nearest nodes that between them can cover the shortfall, and drops JourneyMap waypoints for
them. When the wand is topped up, those waypoints delete themselves.

## What it does

- **Keybind (default `V`)** — plans a refill run from the wand you're holding.
- **Picks nodes intelligently** — a greedy set-cover weighted by travel distance, so it prefers
  a cluster of nearby nodes over three scattered in different directions, and it only sends you
  to nodes that actually hold the aspects you're missing.
- **Estimates node contents** — nodes you're standing near are read live from the tile entity
  (exact). For distant nodes it takes the last amounts you actually observed and projects them
  forward using Thaumcraft's real regeneration rate, so a node you drained an hour ago is
  modelled as partly recovered, not still empty. Waypoint labels say `(live)` or `(~est)` so
  you always know how much to trust the number.
- **Doesn't interfere with draining** — the mod only creates waypoints; it never touches the
  drain interaction. `reservePerNode` (default 1) is purely a planning figure that stops the
  router from counting on the very last point of vis in a node. Thaumcraft handles node
  protection itself via Node Preservation research and wand caps.
- **Cleans up automatically** — waypoints are removed once the wand is full, or (optionally, and
  on by default) as soon as the specific aspects the run was for are topped up. There's also a
  manual clear keybind.
- **Configurable in-game** — Mods → Vis Waypoints → Config. No config file editing.

## Requirements

- Thaumcraft (required)
- TCNodeTracker — the source of scanned nodes. Nodes must be scanned with a Thaumometer first.
- JourneyMap — where the waypoints are created.

All three ship with GTNH. The mod is **client-side only** and declares
`acceptableRemoteVersions = "*"`, so it will never stop you connecting to a server.

## Notes and limits

**"Real-time" node amounts have a hard ceiling.** A node's exact current vis only exists while
its chunk is loaded. Nearby nodes are therefore read exactly; distant ones are an estimate
(last observed + modelled regen). No client-side mod can do better than this without server
support.

**Only nodes you have scanned are known.** This reads TCNodeTracker's database. Nodes you have
never hit with a Thaumometer are invisible to it.

**VisualProspecting is not a node source.** It tracks ore veins and wells and has no concept of
Thaumcraft aura nodes, so despite being the obvious-sounding integration, there is nothing there
to read.

## Building

Standard GTNH buildscript. `./gradlew build`, or push a tag and let the GitHub Actions release
workflow build the jar for you.
