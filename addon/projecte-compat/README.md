# Yu-Gi-Oh! × ProjectE EMC Compatibility

Minecraft 1.20.1 / Forge 47.4.28 / ProjectE PE1.0.1.

This standalone compatibility addon gives positive EMC to every registered item from `yugioh`, `yugioh_interaction_fix`, `yugioh_mdpro_addon`, and `ygostar`. It uses ProjectE's custom EMC IMC registration, so ProjectE remains an optional compile-time API dependency and its code is not bundled into this jar.

The default `config/yugioh-projecte.json` is created on first startup. It controls base card, commemorative card, deck box, disk, table, Gold Card, star, glove shell, and fallback values. The `overrides` object can set exact values by registry ID, for example `"yugioh:blue_eyes_white_dragon": 4096`.

Booster packs receive the value of five base cards. Sealed structure/historical decks receive the value of their card lists plus a deck box. A deck box with contents, and a glove with stars, are rejected by the table slots and learn event, and receive EMC 0. Empty forms remain exchangeable. Newly created gloves start with two stars and must be emptied first. Card rarity and empty glove capability data survive ProjectE normalization. A required EMC guard also runs before ProjectE's no-NBT fast path, independently of NBT processor configuration.

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path ..\..\generated\gradle-home)
gradle clean build --no-daemon
```

Output: `build/libs/yugioh-projecte-compat-1.0.0.jar`. The original game mods directory is never changed by this project; validate in `run-client/mods` before deployment.

For the isolated runtime check, build with `gradle clean build -PcompatSmoke --no-daemon`, then run `python tools/verify_client.py`. The helper requires the test fixture and a fresh PASS from the current client process, checks every registered default item stack, and exports `generated/projecte-compat/item-emc-values.tsv`. Rebuild without `compatSmoke` for release; compare the release classes with the tested classes and exclude the fixture from deployment.
