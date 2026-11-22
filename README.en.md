# RongMC Client and Live Tours

[简体中文](./README.md) · English

Plays camera tours through the live Minecraft world using keyframes. Players can pause, skip or complete a tour from the overlay. The repository also contains the modpack’s first-run device setup code.

## Download and installation

- Version · [v0.4.0-dev+26.3](https://github.com/OpenRongMC/RongMC-Client/releases/tag/v0.4.0-dev%2B26.3) (Prerelease)
- Release asset · `rongmc-client-0.4.0-dev.26.3.jar`
- Runtime · Minecraft Java 26.3, Fabric, Java 25
- Fabric Loader · `>=0.18.4`
- Fabric mod ID · `rongmc_client`
- Dependencies declared by the JAR · [Fabric API](https://modrinth.com/mod/fabric-api)

Place the release JAR in the instance’s `mods/` directory. Client-side setup needs the optional-mod catalog supplied by the pack. For server-managed tours, use compatible copies on both sides. GitHub prereleases and pack-bundled builds may differ, so check the release tag.

## How to try it

Record sample keyframes in creative or spectator mode with F7, and open the editor with F8 or /rongmctour editor. Try /rongmctour in multiplayer and check that completing or skipping restores the camera and player position.

## First-run device setup

The RongMC low-spec pack starts with the minimum tier. Choosing a higher tier enables more optional mods on restart. Newer pack builds with the touch-control option skip Spatial GUI when touch control is enabled.

## Working from source

```sh
./gradlew build
```

Test tour changes in an isolated client and dedicated server.

## Documentation and support

- [Camera editor guide](./docs/camera-editor.md) · [Server configuration example](./examples/rongmc-tour-server.json)
- [Previous README (historical guide, contributors and credits)](https://github.com/OpenRongMC/RongMC-Client/blob/d18a93fcf19a2670580aeb30fcfafddca068876a/README.md)
- [Report a RongMC port issue](https://github.com/OpenRongMC/RongMC-Client/issues) · Include the game version, client and server JAR names, reproduction steps and relevant logs.

## License and credits

See this repository’s [LICENSE](./LICENSE) and the original project’s license, credits and third-party asset notices.
