# RongMC Live Welcome Tours

[简体中文](./README.md) · English


Welcome players with a camera tour through the world they just joined. The camera follows keyframes through live Minecraft scenes while an interactive overlay shows captions and chapter controls. The tour runs without a prerecorded video.

## What changed in the RongMC 26.3 port

The 26.3 port retains ReplayStudio camera editing and multiplayer tours. Pack builds offer five first-run device tiers. Newer builds can enable touch controls and skip Spatial GUI when touch is selected. Available options depend on the pack build.

## Installation

Download **v0.4.0-dev+26.3** from [GitHub Releases](https://github.com/OpenRongMC/RongMC-Client/releases/tag/v0.4.0-dev%2B26.3) for Minecraft Java **26.3** on **Fabric** with **Java 25**. Dependencies include [Fabric API](https://modrinth.com/mod/fabric-api). Place the release JAR in the instance’s `mods/` directory. Client-side setup needs the optional-mod catalog supplied by the pack. For server-managed tours, use compatible copies on both sides. GitHub prereleases and pack-bundled builds may differ, so check the release tag.

## Create a tour in game

The editor uses ReplayStudio pathing. Press **F7** to record a camera position and **F8** to open the editor, or use `/rongmctour editor`. Edit timing, curves, FOV, chapters and captions, then preview or export the route. See the [camera editor guide](./docs/camera-editor.md). Source projects are kept in `config/rongmc-camera/projects/`, with exports under `config/rongmc-camera/exports/`.

## Playing a tour

The overlay supports chapters, previous and next controls, pause, resume, skip and reduced motion. The client can use temporary rendering settings for the tour and restores the player's options afterward. The server moves players to scene anchors, prepares chunks and records a recovery position before starting. Tour completion is saved by player and version.

## Try it in a test world

Use `/rongmctour demo` as an administrator in a single-player creative world with commands enabled. For multiplayer, install the mod and matching Fabric API on both sides. Start with the [sample configuration](./examples/rongmc-tour-server.json), set the real scene coordinates, then configure `config/rongmc-tour-server.json`. The generated server configuration starts with automatic tours disabled.

## Building

Use JDK 25. Run `./gradlew build` for the archive, or `./gradlew runClient` to open a development client. See [LICENSE](./LICENSE) for project licensing.
