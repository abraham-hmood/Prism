# Zombie (MPFB2 pipeline)

The playable zombie for the **Zombies** minigame. Every part of the character is built with
Blender's own tools — MPFB2's human base, its rig, its shoe asset, `bmesh`, cloth simulation,
Cycles bakes. No character geometry is assembled from spheres, cylinders or discs.

```
build.py      human + face targets + sculpt + garments + pose + animation + GLB
materials.py  procedural skin / fabric / leather / metal / eye / tooth shaders and bakes
assets/       MPFB system asset zip (CC0)
out/          GLBs, .blend, bakes
previews/     turnaround, head close-up, sprite
```

## Run

```bash
"C:/Program Files/Blender Foundation/Blender 5.2/blender.exe" -b --factory-startup \
  --python tools/zombie/build.py -- --seed 7 --stage full --variant male --pose stand
```

| flag | meaning |
| --- | --- |
| `--variant male\|female` | body macro + seed |
| `--pose stand\|crawl` | upright shamble, or prone crawl |
| `--stage geom\|full` | clay preview only, or baked PBR + GLB |
| `--bake-res 1024\|2048` | texture size (2048 is the default in `bake_character`) |
| `--bake-samples N` | Cycles samples for the bakes |
| `--no-render`, `--no-cloth`, `--simple`, `--no-anim` | iteration shortcuts |

Outputs per run: `out/zombie_<variant>_<pose>.glb` (skinned, `Walk` or `Crawl` clip),
`out/textures/*.png`, `previews/<variant>_<pose>_{front,side,back,head,head34,sprite}.png`,
triangle count and texture list in the log.

## Pipeline

1. **MPFB2 human** — `HumanService.create_human` (macro: gaunt, aged, narrow), MakeHuman face
   targets for bone structure and hollows, default rig with weights, MPFB eyes, teeth and shoes.
2. **Zombify** — noise displacement on the body surface, aimed at vertex groups: temples,
   cheek hollows, brow ridge, eye sockets, lips, rotted forearms. All displacement is a surface
   change; nothing is added as a separate part.
3. **Clothing** — shirt and trousers are cut from the body surface itself, offset, cloth-simulated
   against the body and then detailed with attached plackets, pockets, collar, cuffs, belt, buckle.
   Knee tears are noise-masked deletions with frayed edges.
4. **Materials** — node shaders (mottled necrotic skin, wound mask, dried blood, grime, fabric
   weave, leather, metal) baked to albedo / normal / roughness / AO with Cycles on CPU.
5. **Pose** — `stand` is the reference silhouette; `crawl` is posed in rest space (limb
   directions in the standing frame), then the rig is tipped face-down, dropped onto the floor
   and centred.
6. **Animation** — `build_animation` re-poses the rig every frame through the same pose code and
   keys the result, so the clip is made of the exact poses used for stills. 25 keys at 24 fps,
   `Walk` (shamble) or `Crawl` (hand-over-hand).
7. **Export** — GLB with rig, skin and one named clip; the clip name is patched into the GLB
   JSON because Blender 5 exports slotted actions as `Animation`.

## In the game

`ZombiesAssets.build()` copies `zombie_{male,female}_{stand,crawl}.glb` out of
`<documents>/ZombiesCraft/models/` (or a meshnet baseline folder, Settings → Minigames →
Zombies) into the Godot project's `assets/models/`. `scripts/zombie.gd` instances the GLB when
it is present, hides the fallback body parts and drives its `Walk` clip; without the file the
primitive fallback still runs.

## Known gaps

Procedural sculpt + procedural textures get a readable corpse silhouette, correct clothing and
gore, but not the subsurface skin, pore-level normals and hand-sculpted wrinkles of the
reference renders. Those come from a scanned/sculpted high-poly source with baked normal maps.
