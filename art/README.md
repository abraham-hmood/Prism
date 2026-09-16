# Aether's art pipeline

Everything in `app/src/main/assets/3dmodels/` is generated. This is what generates it, and why it
has to exist rather than being a one-off export from Blender's UI.

The source is a Character Creator 3 figure imported into Blender with the CC/iC Blender Tools
addon, living at `blender file.blend` (not in this repo -- it is 303 MB). It is a Rigify rig of 724
bones, of which 179 deform, and every texture it owns is *packed inside the .blend*.

**Blender's own .obj exporter cannot produce a usable asset from it.** Every material drives Base
Color through an `rl_*_shader` node group, and the exporter only emits `map_Kd` when an Image
Texture feeds the Principled BSDF directly. Given this file it writes a flat `Kd 0.800000` for all
nineteen materials and silently drops the entire texture set, which renders her as a grey clay
sculpt. Both scripts here exist to work around that by reaching into the .blend and taking the
images out themselves.

Run them against whatever Blender made the file (5.2 LTS at time of writing):

```
blender -b "blender file.blend" --factory-startup --python <script> -- <output-dir> [flags]
```

## `export_mesh_and_textures.py`

Writes `Aether.obj`, `Aether.mtl` and `aether_tex/`. Unpacks each material's diffuse and opacity
maps, scales them per the `PLAN` table (budgeted against how much screen each covers -- the face
gets 2048, small parts get 512), and the caller writes the `.mtl` bindings by hand afterwards.

Things encoded here that are easy to get wrong:

- **Clothing is excluded.** `EXPORT_OBJECTS` deliberately omits `Bra` and `Underwear_Bottoms`
  because custom clothing is coming later. Add a mesh there to include it.
- **The scalp gets a built texture.** `Scalp_Transparency_Transparency` is the cap the hair cards
  grow out of and the export gives it an opacity mask but no diffuse at all. Dropped, it leaves
  bare scalp skin showing through the gaps between hair cards -- a visible bald patch. It is
  therefore given the hair's own average colour, darkened towards the roots, on the scalp's real
  alpha mask.
- **The eyeball is dropped and the cornea kept.** The eyeball's sclera map is veins around a blank
  grey disc; the iris lives on the cornea shell, whose map carries the whole eye. The two shells
  are also duplicates offset by a ten-thousandth of a unit, so drawing both z-fights.
- **Vertex colours are turned off.** They only ever carried hair shading, and over a real diffuse
  map they would just dim it.

The UVs are UDIM-tiled (head 0-1, torso 1-2, arms 2-3, legs 3-4, nails 4-5, lashes 5-6), which is
why `AetherModelView` samples with `GL_REPEAT`. Do not "fix" that to clamp.

## `build_idle_animation.py`

Writes `Aether_Idle.fbx` -- the default idle, 360 frames at 30fps, looping. `--poseonly` skips the
bake and renders the rest pose only, which is the fast path for iterating on the pose; `--fbx`
exports.

- **The arms run on IK, and that is the rig's decision.** Its deform bones do not follow the ORG
  chain -- `DEF-upper_arm` copies a *tweak* bone and stretches to the next, and the tweaks ride the
  IK chain in the shipped state. Flipping `IK_FK` does re-route them, but only once the drivers
  behind it re-evaluate, and that proved unreliable across a frame-by-frame bake: the FK controls
  posed perfectly while the mesh stayed in its A-pose.
- **The elbow angle is solved by measurement, not by guessing.** `pole_vector` is 0 on this rig, so
  the pole target is inert and the elbow swings with the IK parent's roll instead. `solve_elbow()`
  sweeps that roll and reads where `DEF-forearm` actually lands.
- **Verify against `DEF-` bones, never the controls.** The controls will happily report the pose you
  asked for while the mesh ignores it.
- **Batch the depsgraph updates.** `view_layer.update()` on this rig costs a good fraction of a
  second; calling it per placed bone turned a 2-minute bake into a 10-minute one.

## The arrival, and why it needs no blend

`art/animation_frames/` holds five stills of a *different* woman, pulled out of a GIF. They carry no
motion data and nothing can be extracted from them mechanically -- they are pose reference, used the
same way the idle was authored. The beat in them is a head lowered and then raised to camera with a
small smile. The walk in the reference is not reproduced, because at Prism's crop you only see her
from the hips up and none of it would be visible.

**The intro is not a separate clip that hands over to the loop.** It is the loop's own opening phases
with an extra pose added and faded out (`intro_pose`). At the end of the fade the offset is exactly
zero, so the last intro frame *is* the idle at that phase -- there is no cross-fade to tune and no
seam to hunt for. It also means she breathes and sways while she arrives instead of being frozen
until the loop takes over.

Playback is therefore just counting. Frames below `INTRO` read the intro block; everything after
reads the loop at `frame % loopFrames`, which is the same phase the intro would have reached had it
kept going. Frame 74 is the intro at phase 74 with its offset already at zero; frame 75 is the loop
at phase 75.

That is also why this does NOT ping-pong. Reversal is the fix for a clip that cannot loop, and the
idle already loops by construction -- the exporter measures the join and prints it. Reversing would
double the period, put a zero-velocity turnaround at each end, and play breathing backwards.

**Blinks moved from shape keys to bones.** They were authored as `Eye_Blink_L/R` shape keys, which
the FBX carries and `.pskn` does not -- so Aether never blinked in Prism. `apply_lids` drives the
lid bones instead, which reach the device through the existing bone pipeline for free. The lid
rotation is solved by measurement (`solve_lids`) for the same reason the elbow is: the axis and sign
a face bone needs are a property of how the rig was built, not something to guess. The old
`blinks()` is kept only as a record and is no longer called.

## `export_skinned.py`

Writes `app/src/main/assets/3dmodels/Aether.pskn` -- the skinned, animated asset Prism actually
draws -- and with `--fbx`, a self-contained textured `Aether_Idle.fbx` alongside it. Imports
`build_idle_animation.py` so the idle is authored in exactly one place.

```
blender -b "blender file.blend" --factory-startup --python art/blender/export_skinned.py \
        -- app/src/main/assets/3dmodels art --fbx
```

### Why Prism does not read the .fbx

Using it directly would mean owning an FBX parser: deflate-compressed property arrays, a node graph,
and skinning that lives somewhere other than the animation driving it. Since this pipeline sits at
the other end of the pipe it writes what the renderer reads instead -- the vertex block is already in
GL's interleaved layout and uploads with no repacking, and every bone matrix arrives with its
inverse bind pre-multiplied in, so the device never walks a hierarchy.

`.pskn` layout, little-endian: `'PSKN'`, version, boneCount, frameCount, fps, batchCount; then per
batch a name, a texture path, a cutout flag, and vertex/index blocks (40 bytes per vertex --
position, normal, uv, four bone indices as bytes, four weights as bytes); then
`frameCount x boneCount x 12` floats of row-major 3x4 skinning matrices. Slot 0 is always identity.

### Traps in here

- **Gather the mesh at REST.** `evaluated_get()` with the rig posed returns vertices that already
  have the armature applied; skinning those again on the device deforms her twice. The gather flips
  `pose_position` to `'REST'` so every other modifier still runs while the armature contributes
  identity -- which is the bind pose the matrices transform from.
- **Identity pruning barely helps, and that is not a bug.** The matrices are in ARMATURE space, so a
  brow or a fingertip stops being identity the moment the spine sways even though its own local
  rotation never changes. Only 12 of 171 bones fold away. The remaining 160 are 7680 bytes of
  std140 uniform block against the 16384 every ES 3.0 device must provide.
- **Materials have to be flattened before the FBX export.** The exporter reads a material by walking
  back from Principled's Base Color, and on this figure that comes from an `rl_*_shader` group -- so
  it writes no Texture and no Video node at all and the .fbx lands completely untextured.
  `rewire_materials()` rebuilds each tree as "image straight into Base Color", the one shape the
  exporter can follow. It wires the downscaled maps this pipeline already wrote, not the
  4096-square originals, so the .fbx and Prism cannot drift on what she looks like.
- **Never hold a reference into evaluated mesh data.** `uv_layer[i].uv`, and anything else the
  evaluated mesh owns, is freed by `to_mesh_clear()`. Copying the values out is not tidiness, it is
  correctness: holding the reference and reading it later is a use-after-free, and it fails
  deceptively -- the freed block usually still holds the right numbers, so meshes gathered LAST come
  out perfect while the ones gathered FIRST read whatever reused their memory. This shipped once as
  UVs around -6.6e36 across most of her body, and the only visible symptom was that her eyes had
  disappeared, because a skin map sampled at the wrong coordinate still looks like skin. `co` and
  `normal` are safe only because the matrix multiply builds fresh Vectors; everything is stored as
  plain float tuples now so it cannot happen again.
- **A bone-parented mesh must be bound rigidly, and NOT to the bone it names.** `Hair` is parented
  to the `head` bone *and* carries an armature modifier weighting it across `DEF-spine.003..006`.
  Blender composes both -- the object matrix rides the head, then the modifier deforms the result --
  and this format cannot express that, because a vertex carries four weighted bone matrices, not a
  per-vertex composition of a parent transform with a weighted sum. Exporting only the weights drops
  the head's motion entirely and the hair visibly comes away from the skull.
  The obvious fix makes it worse. `head` is a Rigify CONTROL bone: nothing is weighted to it, so
  `collect_bones()` never gathers it, no matrix is ever baked, and `prune()` folds it onto the shared
  static slot -- binding the hair to its own parent left the hair completely motionless. The control
  bone has to be mapped onto the deform bone that moves with it, and that mapping is **not derivable
  from the name**: the head's deform bone is `DEF-spine.006`. It *is* derivable from the skeleton,
  because a deform bone and the control driving it share a rest position, which is what
  `resolve_rigid_bone()` matches on. `verify_rigid_binds()` then proves the substitution after the
  bake, in centimetres at the mesh's own position, and aborts rather than shipping a guess.
  What this gives up is the soft spine-chain falloff -- which was never visible anyway, since the
  cloth simulation that would have made the hair move independently is stripped before the bake.
- **Measure rigidity with a DISTANCE, never a component-wise offset.** This one produced three wrong
  diagnoses in a row. Comparing `hair_centroid - head_centroid` between frames looks like it detects
  detachment and does not: under a rotation about the neck, a point further from the joint travels
  further, so that offset vector *rotates* and its components change even when the two points are
  welded together. It reported centimetres of "drift" for geometry that was rigid by construction.
  Distance is invariant under rotation and translation, so the honest check is whether
  `|hair_point - skull_point|` is constant across every frame -- and it must sample skull vertices
  driven almost entirely by the same bone, or the jaw's own motion pollutes the reading. The fixed
  binding holds that distance to 0.0000 cm over all 435 frames.
- **`validate()` runs before the write and aborts on out-of-range UVs, positions or bone slots.**
  It exists because of the bug above. If it ever fires, do not loosen it -- find out what it caught.
- **The framing constants in `AetherModelView` depend on the pose.** Her bind silhouette is
  +/-0.724 wide; posed with her arms down it is +/-0.228. A crop tuned for the bind pose clipped her
  elbows. Re-tune `FRAME_HALF_*_FRACTION` if the idle's pose ever changes materially.
