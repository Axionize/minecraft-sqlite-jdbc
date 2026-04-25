# SQLite JDBC for Minecraft

A maintained fork of the dormant SQLite JDBC for Minecraft mod, now tracking the **latest [Xerial sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) release** with auto-bumps whenever upstream cuts a new version.

The mod does nothing on its own — it ships the SQLite JDBC driver onto the server's classpath so other plugins/mods that want to read or write `.db`/`.sqlite` files via JDBC don't each have to bundle their own copy.

Typical consumers: [Dynmap](https://www.curseforge.com/minecraft/mc-mods/dynmap), [Plan](https://github.com/plan-player-analytics/Plan), [LuckPerms](https://luckperms.net/), [Grim Anti-Cheat](https://github.com/GrimAnticheat/Grim), and any plugin that wants embedded SQLite without redistributing 10MB of native binaries themselves.

## What's in the jar

`org.xerial:sqlite-jdbc:3.53.0.0` plus minimal loader stubs for Spigot, Forge 1.12, Forge 1.13–1.16, Forge 1.17–1.20, NeoForge 1.21+, and Fabric. The driver classes stay at their canonical `org.sqlite.*` paths — no relocation — so consumers find them with plain `Class.forName("org.sqlite.JDBC")`. `META-INF/services/java.sql.Driver` is preserved so the driver auto-registers with `DriverManager`.

The xerial driver bundles native SQLite binaries for every common platform/architecture (linux x64/aarch64, macos x64/arm64, windows x64). They get extracted to the JVM's tempdir on first use.

## Compatibility

| Loader | MC versions | Notes |
|---|---|---|
| Bukkit / Spigot / Paper / Folia / Purpur | 1.8 → current | drop into `plugins/` |
| Fabric | 1.16.1 → current | needs Fabric Loader 0.14+ |
| Forge | 1.12 → 1.20 | universal jar, no Mixins |
| NeoForge | 1.21 → current | drop into `mods/` |

Java 8+ required. Plain BukkitAPI servers without Java 8 (anything older than ~1.8.8) won't be able to load any modern xerial release; that's an upstream constraint, not this mod.

### When you actually need this mod

Most CraftBukkit/Spigot/Paper servers from 1.13+ already ship a bundled `sqlite-jdbc` on the server classpath, and modern xerial releases there are recent enough for typical needs. You need this mod when:

- You're on **Fabric** or **NeoForge** — vanilla Minecraft ships no JDBC drivers at all
- You're on a **legacy Bukkit fork that's stripped the bundled driver**
- Your server's bundled SQLite engine is **too old for features you need** (e.g. `ON CONFLICT DO UPDATE` needs engine 3.24+, only present on CraftBukkit 1.13.2+; `RETURNING` needs 3.35+)

### Bundled SQLite engine versions on common Bukkit lines

For reference if you're wondering whether the server's bundled driver is recent enough:

| CraftBukkit / Paper line | Bundled SQLite engine |
|---|---|
| 1.8 – 1.10 | 3.7.2 |
| 1.11 | 3.16.1 |
| 1.12 | 3.21.0.1 |
| 1.13 | 3.25.2 |
| 1.14 | 3.28.0 |
| 1.15 | 3.30.1 |
| 1.16 – 1.17 | 3.34.0 |
| 1.20.6 | 3.45.3.0 |
| Paper 1.21.4 | 3.47.0.0 |
| Paper 1.21.5 – 1.21.11 | 3.49.1.0 |
| master / 26.x | 3.51.3.0 |

This mod ships engine 3.53.0.0, the current xerial release. If you drop it onto a server that already has a bundled `sqlite-jdbc`, the server's bundled copy generally wins driver-class resolution at boot — `DriverManager.getConnection("jdbc:sqlite:…")` returns whichever driver registered first, and the bundled driver registers earlier in startup.

## Using it from a plugin or mod

Declare sqlite-jdbc as `compileOnly`:

```kotlin
compileOnly("org.xerial:sqlite-jdbc:3.53.0.0")
```

Probe at startup:

```java
try {
    Class.forName("org.sqlite.JDBC");
} catch (ClassNotFoundException e) {
    getLogger().warning("SQLite backend disabled — install minecraft-sqlite-jdbc");
    return;
}
try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("data.db"))) {
    // ...
}
```

On Paper 1.17+ each plugin's classloader is isolated. Add this mod to your `plugin.yml` `softdepend` so the driver classes are visible to your plugin:

```yaml
softdepend: [minecraft-sqlite-jdbc]
```

Fabric and NeoForge unify all mods on one classloader, so no equivalent declaration is needed there.

## Versioning

The jar version tracks Xerial's `sqlite-jdbc` release one-to-one. `3.53.0.0+2026-04-14` ships engine 3.53.0.0; the suffix is the build date. A scheduled GitHub Action checks Maven Central daily — when xerial cuts a new release, an auto-merge PR opens here and the Modrinth release goes out automatically.

## License

Apache 2.0 (Xerial / Taro L. Saito). The repackage adds no functional changes and inherits that license. Full text in [`LICENSE`](https://github.com/Axionize/minecraft-sqlite-jdbc/blob/main/LICENSE).

---

Issues, source: [GitHub](https://github.com/Axionize/minecraft-sqlite-jdbc).
