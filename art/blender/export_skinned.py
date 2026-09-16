"""
Exports Aether as a skinned, animated asset Prism can actually draw, plus a textured FBX.

WHY A CUSTOM FORMAT AND NOT THE FBX. Prism's renderer would have to parse binary FBX to use it --
a format of deflate-compressed property arrays and node graphs whose animation lives in a different
place than its skinning, which is a great deal of parser to write and maintain for one asset. Since
this pipeline already owns both ends, it writes exactly what the renderer reads: a header, material
batches carrying the same textures the static model uses, four bone influences per vertex, and one
pre-multiplied skinning matrix per bone per frame. There is no hierarchy to walk and no
interpolation to solve at load time.

BONES THAT NEVER MOVE ARE COLLAPSED, though far fewer qualify than you would expect. The
pruning pass folds every bone whose skinning matrix stays identity for all 360 frames onto one
shared slot, since a vertex weighted to a bone that never moves and a vertex weighted to identity
are the same vertex. In practice this only removes 12 of 171: these matrices are in ARMATURE space,
so a finger or a brow inherits its parent's motion and stops being identity the moment the spine
sways, even though its own local rotation never changes. The remaining 160 slots are 7680 bytes of
uniform block against the 16384 every ES 3.0 device must provide, so they fit regardless.
"""
import os
import struct
import sys

import bpy
from mathutils import Matrix

sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import build_idle_animation as idle           # noqa: E402  (needs the path append above)

ARGS = sys.argv[sys.argv.index("--") + 1:]
OUT_DIR = os.path.abspath(ARGS[0])
FBX_DIR = os.path.abspath(ARGS[1]) if len(ARGS) > 1 and not ARGS[1].startswith("--") else OUT_DIR
MAGIC = b"PSKN"
VERSION = 2
MAX_INFLUENCES = 4
IDENTITY_EPSILON = 1e-4
# How far a batch may sit from the body before the export refuses it, in metres.
DETACHED_LIMIT = 0.25

rig = idle.rig
scene = bpy.context.scene

# Which texture each material samples, mirroring what export_mesh_and_textures.py wrote. Materials
# absent from here are the ones the renderer already drops -- see AetherModel.isDrawable.
TEXTURES = {
    "Skin_Head": ("Skin_Head.jpg", False),
    "Skin_Body": ("Skin_Body.jpg", False),
    "Skin_Arm": ("Skin_Arm.jpg", False),
    "Skin_Leg": ("Skin_Leg.jpg", False),
    "Nails": ("Nails.jpg", False),
    "Eyelash": ("Eyelash.webp", True),
    "Hair_Transparency_Transparency": ("Hair_Transparency_Transparency.webp", True),
    "Scalp_Transparency_Transparency": ("Scalp_Transparency_Transparency.webp", True),
    "Upper_Teeth": ("Upper_Teeth.jpg", False),
    "Lower_Teeth": ("Lower_Teeth.jpg", False),
    "Tongue": ("Tongue.jpg", False),
    "Cornea_L": ("Cornea_L.jpg", False),
    "Cornea_R": ("Cornea_R.jpg", False),
}
# Discovered from the scene rather than named, so a rename in the .blend cannot abort the export.
# See build_idle_animation.character_meshes.
MESHES = None       # resolved in main(), once TEXTURES is known


def build_animation():
    """Poses the rig and bakes the idle, reusing the authored animation rather than a copy of it."""
    idle.clean()
    idle.bake()


def collect_bones():
    """Every deform bone actually referenced by a vertex group on the exported meshes."""
    used = set()
    for name in MESHES:
        obj = bpy.data.objects[name]
        for vg in obj.vertex_groups:
            if vg.name in rig.pose.bones:
                used.add(vg.name)
    return sorted(used)


def skin_matrices(bones):
    """`pose x bind^-1` per bone per frame, in armature space.

    Pre-multiplying the inverse bind here rather than on the device means the shader does nothing
    but a weighted sum of four matrices, and the runtime never has to know the skeleton's shape.
    """
    inverse_bind = {}
    for name in bones:
        inverse_bind[name] = rig.data.bones[name].matrix_local.inverted()

    frames = []
    for frame in range(1, idle.TOTAL + 1):
        scene.frame_set(frame)
        bpy.context.view_layer.update()
        row = []
        for name in bones:
            row.append(rig.pose.bones[name].matrix @ inverse_bind[name])
        frames.append(row)
    return frames


def prune(bones, frames):
    """Collapses every bone that never leaves its bind pose onto one shared identity slot."""
    moving = []
    for i, name in enumerate(bones):
        deviation = 0.0
        for row in frames:
            m = row[i]
            for r in range(3):
                for c in range(4):
                    ideal = 1.0 if r == c else 0.0
                    deviation = max(deviation, abs(m[r][c] - ideal))
            if deviation > IDENTITY_EPSILON:
                break
        if deviation > IDENTITY_EPSILON:
            moving.append(name)

    # Slot 0 is the identity every static bone folds into.
    order = ["<static>"] + moving
    index = {name: 0 for name in bones}
    for slot, name in enumerate(moving, start=1):
        index[name] = slot
    print("<<< bones: %d weighted -> %d moving (+1 shared static slot)" % (len(bones), len(moving)))
    return order, index, moving


# Bone-parented meshes, resolved once and reused by gather_mesh and the post-bake check.
RIGID_BINDS = {}


def deformable_bones():
    """Exactly the bones collect_bones() will bake: those used as a vertex group somewhere."""
    used = set()
    for name in MESHES:
        obj = bpy.data.objects[name]
        for vg in obj.vertex_groups:
            if vg.name in rig.pose.bones:
                used.add(vg.name)
    return used


def resolve_rigid_bone(obj):
    """The baked bone a bone-parented object should ride, or None if it is not bone-parented.

    A BONE-PARENTED OBJECT RIDES THAT BONE, and its vertex groups are a second, conflicting binding
    on top. `Hair` is parented to `head` AND carries an armature modifier weighting it across
    DEF-spine.003..006. Blender composes both: the object matrix moves it with the head, then the
    modifier deforms it again by the spine chain. This format cannot express that -- a vertex
    carries four weighted bone matrices, not a per-vertex composition of a parent transform with a
    weighted sum -- so exporting only the weights dropped the head's motion entirely, and the hair
    drifted up to 4.8 cm away from her head as she raised it.

    THE PARENT BONE ITSELF IS USUALLY NOT EXPORTABLE, which is the trap that made the first fix
    worse than the bug. `head` is a Rigify CONTROL bone: nothing is weighted to it, so
    collect_bones() never gathers it, no matrix is ever baked for it, and prune() folds it onto the
    shared static slot. Binding the hair to it left the hair completely motionless -- measurably
    worse than the drift it replaced.

    So the control bone has to be mapped onto the deform bone that moves with it. That mapping is
    NOT derivable from the name: Rigify calls the head's deform bone DEF-spine.006, which shares no
    stem with `head`. It IS derivable from the skeleton, because a deform bone and the control that
    drives it occupy the same place in the rest pose. Measured on this rig, DEF-spine.006 sits on
    `head` to within a rounding error and reproduces its motion at the hair to 0.000 cm.
    """
    if obj.parent is not rig or obj.parent_type != 'BONE' or not obj.parent_bone:
        return None
    parent = obj.parent_bone
    if parent not in rig.data.bones:
        print("<<< WARNING %s is parented to unknown bone %r; leaving it on its weights"
              % (obj.name, parent))
        return None

    baked = deformable_bones()
    if parent in baked:
        return parent

    target = rig.data.bones[parent].matrix_local
    best, best_err = None, None
    for name in sorted(baked):
        other = rig.data.bones[name].matrix_local
        err = max(abs(target[r][c] - other[r][c]) for r in range(4) for c in range(4))
        if best_err is None or err < best_err:
            best, best_err = name, err

    # A loose tolerance on purpose: it only has to be near enough to be the same joint, and the
    # post-bake check below is what actually proves the choice, in centimetres at the hair itself.
    if best is None or best_err > 1e-3:
        print("<<< WARNING no baked bone sits on %r (closest %s, off by %.6f); "
              "leaving %s on its weights" % (parent, best, best_err or -1.0, obj.name))
        return None

    print("<<< %s is bone-parented to %r, which is not a deform bone; riding %s instead "
          "(rest poses agree to %.2e)" % (obj.name, parent, best, best_err))
    return best


def gather_mesh(obj_name):
    """Triangulated, per-material vertex data with four bone influences each.

    EVALUATED AT REST, which is the whole trick. Asking for the evaluated mesh with the rig in its
    posed state returns vertices that already have the armature applied, and skinning those a
    second time on the device would deform her twice. Flipping the armature to REST leaves every
    other modifier running while the armature itself contributes identity, which is exactly the
    bind pose the skinning matrices are built to transform from.
    """
    obj = bpy.data.objects[obj_name]
    rigid_bone = resolve_rigid_bone(obj)

    # A BONE-PARENTED MESH MUST BE GATHERED POSED, WHICH IS THE OPPOSITE OF EVERY OTHER MESH.
    #
    # Every object-parented mesh is gathered at REST, because the evaluated mesh of a posed rig
    # already has the armature applied and skinning it again on the device would deform it twice.
    # `Hair` cannot be gathered that way, and measuring it says so plainly. In a headless load its
    # bounding box in armature space is:
    #
    #     posed, full modifier stack   centre(-0.739,-0.702,-0.251)  size(0.931,1.571,1.578)
    #     REST, action detached        centre(-1.085,-0.035, 0.316)  size(0.132,0.111,0.136)
    #
    # At REST it is a 13 cm blob a metre off her body -- and identical with the modifier stack
    # disabled entirely, so nothing in that stack is putting it back. Its shape and its placement
    # both come from being evaluated POSED; `matrix_parent_inverse` carries a -1.084 X offset that
    # only cancels against the posed bone. Gathering it at REST exports the blob, which is the hair
    # sitting beside her head instead of on it.
    #
    # So it is gathered posed, and the pose is then divided back out. Storing
    #
    #     bind @ pose^-1 @ armature_space_vertex
    #
    # means the runtime's `pose(f) @ bind^-1` reproduces the armature-space vertex exactly at the
    # gather pose, and rotates it with the head from there. No double deformation, because the only
    # bone driving these vertices is the one whose transform was just removed.
    previous = rig.data.pose_position
    previous_action = None
    if rigid_bone is None:
        # THE ACTION HAS TO GO, not just the pose position. With a baked action loaded, a parent
        # transform is read from the ANIMATED rig even in REST. Detaching it for the duration of
        # the gather is what makes the bind pose mean the bind pose.
        if rig.animation_data:
            previous_action = rig.animation_data.action
            rig.animation_data.action = None
        rig.data.pose_position = 'REST'
    bpy.context.view_layer.update()

    # Captured after the update, so it is the pose the vertices are actually gathered under.
    undo_pose = None
    if rigid_bone is not None:
        undo_pose = (
            rig.data.bones[rigid_bone].matrix_local
            @ rig.pose.bones[rigid_bone].matrix.inverted()
        )

    depsgraph = bpy.context.evaluated_depsgraph_get()
    evaluated = obj.evaluated_get(depsgraph)
    mesh = evaluated.to_mesh()
    mesh.calc_loop_triangles()
    try:
        mesh.calc_normals_split()
    except AttributeError:
        pass                                  # Blender 4.1+ computes split normals automatically.

    uv_layer = mesh.uv_layers.active.data if mesh.uv_layers.active else None
    group_names = [g.name for g in obj.vertex_groups]
    to_armature = rig.matrix_world.inverted() @ obj.matrix_world
    if undo_pose is not None:
        to_armature = undo_pose @ to_armature

    # A bone-parented mesh rides one bone rigidly, and its vertex groups are a second, conflicting
    # binding this format cannot compose with the first. What that gives up is the soft spine-chain
    # falloff, which was never visible anyway -- the cloth simulation that would have made the hair
    # move independently is stripped before the bake.
    if rigid_bone is not None:
        RIGID_BINDS[obj_name] = (obj.parent_bone, rigid_bone)

    # Top four influences per vertex, normalised. More than four buys nothing visible on a figure
    # this size and would widen every vertex by eight bytes.
    influences = []
    for v in mesh.vertices:
        pairs = []
        for g in v.groups:
            if g.group >= len(group_names):
                continue
            name = group_names[g.group]
            if name not in rig.pose.bones or g.weight <= 0.0:
                continue
            pairs.append((g.weight, name))
        if rigid_bone is not None:
            pairs = [(1.0, rigid_bone)]
        pairs.sort(key=lambda wn: wn[0], reverse=True)
        pairs = pairs[:MAX_INFLUENCES]
        total = sum(w for w, _ in pairs)
        if total <= 0.0:
            pairs = [(1.0, None)]                 # None == the shared static slot
            total = 1.0
        while len(pairs) < MAX_INFLUENCES:
            pairs.append((0.0, None))
        influences.append([(w / total, s) for w, s in pairs])

    batches = {}
    for tri in mesh.loop_triangles:
        material = mesh.materials[tri.material_index] if tri.material_index < len(mesh.materials) else None
        if material is None or material.name not in TEXTURES:
            continue
        bucket = batches.setdefault(material.name, {"verts": [], "index": {}, "tris": []})
        for loop_index, vert_index in zip(tri.loops, tri.vertices):
            # Into the armature's object space, which is where the skinning matrices live. Both
            # objects sit at the origin in this file, so this is an identity in practice -- it is
            # here so a re-export that moves either one does not silently misplace her.
            normal = (to_armature.to_3x3() @ mesh.loops[loop_index].normal).normalized()
            # COPIED OUT, NOT REFERENCED. `uv_layer[i].uv` is a view onto memory the evaluated mesh
            # owns, and `to_mesh_clear()` below frees it -- so holding the Vector and reading it
            # later in write_packed() is a use-after-free. It fails in the nastiest possible way:
            # the freed block usually still holds the right numbers, so the meshes gathered last
            # came out correct while the ones gathered first read whatever had reused their memory.
            # That shipped once as garbage UVs over most of her body, visible only as missing eyes.
            # `co` and `normal` above are already fresh Vectors because the matrix multiply builds
            # new ones; these are plain float tuples so nothing here can dangle again.
            raw_uv = uv_layer[loop_index].uv if uv_layer else None
            uv = (raw_uv[0], raw_uv[1]) if raw_uv is not None else (0.0, 0.0)
            pos = to_armature @ mesh.vertices[vert_index].co
            co = (pos.x, pos.y, pos.z)
            nrm = (normal.x, normal.y, normal.z)
            inf = influences[vert_index]
            key = (vert_index,
                   round(nrm[0], 4), round(nrm[1], 4), round(nrm[2], 4),
                   round(uv[0], 5), round(uv[1], 5))
            slot = bucket["index"].get(key)
            if slot is None:
                slot = len(bucket["verts"])
                bucket["index"][key] = slot
                bucket["verts"].append((co, nrm, uv, inf))
            bucket["tris"].append(slot)

    evaluated.to_mesh_clear()
    rig.data.pose_position = previous
    if previous_action is not None:
        rig.animation_data.action = previous_action
    bpy.context.view_layer.update()
    return batches



def rewire_materials(tex_dir):
    """Replaces each cc3 node tree with a minimal Principled BSDF fed by the exported texture.

    THIS IS WHAT PUTS MATERIALS ON THE FBX. Blender's FBX exporter reads a material by walking back
    from the Principled BSDF's Base Color, and on this figure that input comes from an `rl_*_shader`
    group -- so the exporter finds no image, writes no Texture and no Video node, and produces an
    .fbx carrying sixteen DiffuseColor properties and nothing else. Flattening each material to
    "image straight into Base Color" is the only shape the exporter can actually follow.

    The images wired in are the DOWNSCALED maps this pipeline already wrote, not the 4096-square
    originals packed in the .blend. Embedding those would add tens of megabytes to the .fbx to carry
    detail nothing samples, and it would let the .fbx and Prism drift apart on what she looks like.
    """
    wired = 0
    for name, (filename, cutout) in TEXTURES.items():
        mat = bpy.data.materials.get(name)
        if mat is None:
            continue
        # ABSOLUTE, because bpy.data.images.load resolves a relative path against the .blend's own
        # directory rather than the process working directory -- and the .blend lives nowhere near
        # the repo. The .pskn write above uses plain open() and is unaffected, which is exactly how
        # this managed to half-succeed.
        path = os.path.abspath(os.path.join(tex_dir, filename))
        if not os.path.exists(path):
            print("<<< SKIP rewire, missing", path)
            continue

        mat.use_nodes = True
        tree = mat.node_tree
        tree.nodes.clear()
        output = tree.nodes.new("ShaderNodeOutputMaterial")
        principled = tree.nodes.new("ShaderNodeBsdfPrincipled")
        image = tree.nodes.new("ShaderNodeTexImage")
        image.image = bpy.data.images.load(path, check_existing=True)
        tree.links.new(image.outputs["Color"], principled.inputs["Base Color"])
        if cutout:
            tree.links.new(image.outputs["Alpha"], principled.inputs["Alpha"])
            mat.blend_method = 'CLIP' if hasattr(mat, "blend_method") else mat.blend_method
        tree.links.new(principled.outputs["BSDF"], output.inputs["Surface"])
        output.location = (300, 0)
        image.location = (-300, 0)
        wired += 1
    print("<<< rewired %d materials for FBX export" % wired)


def export_fbx(path):
    view_layer = bpy.context.view_layer
    for o in view_layer.objects:
        try:
            o.select_set(False)
        except RuntimeError:
            pass
    rig.select_set(True)
    for name in MESHES:
        obj = bpy.data.objects.get(name)
        if obj is not None:
            obj.select_set(True)
    view_layer.objects.active = rig

    options = dict(
        filepath=path,
        use_selection=True,
        object_types={'ARMATURE', 'MESH'},
        # Only the DEF- bones survive. The control rig is 724 bones of IK targets, mechanism and
        # widgets that mean nothing outside Blender; the ones that deform her are what an engine needs.
        use_armature_deform_only=True,
        add_leaf_bones=False,
        bake_anim=True,
        bake_anim_use_all_bones=True,
        bake_anim_use_nla_strips=False,
        bake_anim_use_all_actions=False,
        bake_anim_force_startend_keying=True,
        bake_anim_step=1.0,
        # No curve simplification. The motion is a sum of oscillators sampled per frame, and letting
        # the exporter thin the keys is how a smooth idle becomes a stepped one.
        bake_anim_simplify_factor=0.0,
        mesh_smooth_type='FACE',
        path_mode='COPY',
        embed_textures=True,
        axis_forward='-Z',
        axis_up='Y',
    )
    known = set(bpy.ops.export_scene.fbx.get_rna_type().properties.keys())
    bpy.ops.export_scene.fbx(**{k: v for k, v in options.items() if k in known})
    print("<<< FBX", path, os.path.getsize(path), "bytes")


def remap_influences(all_batches, bone_index):
    """Turns the bone names captured during the gather into the pruned slot numbers."""
    for _, data in all_batches:
        rebuilt = []
        for co, nrm, uv, inf in data["verts"]:
            rebuilt.append((co, nrm, uv,
                            [(w, 0 if n is None else bone_index.get(n, 0)) for w, n in inf]))
        data["verts"] = rebuilt


def verify_rigid_binds(bones, frames):
    """Proves each substituted bone really moves like the bone the mesh is parented to.

    THIS EXISTS BECAUSE THE FIRST FIX SHIPPED AND WAS WORSE THAN THE BUG. Binding the hair to its
    parent bone looked right, exported cleanly, passed every check here, and froze the hair solid,
    because the parent was a control bone that never gets baked. Nothing in the pipeline noticed;
    it took measuring centimetres of drift out of the finished .pskn to see it.

    So the substitution is checked in the units that matter -- how far apart the two bones would
    carry the mesh, in centimetres, at the mesh's own position, across the whole animation. A
    substitute that is not the same joint shows up here immediately, and the export aborts instead
    of writing another asset that has to be measured after the fact to be trusted.
    """
    if not RIGID_BINDS:
        return
    lookup = {name: i for i, name in enumerate(bones)}
    problems = []
    for obj_name, (parent, chosen) in sorted(RIGID_BINDS.items()):
        obj = bpy.data.objects[obj_name]
        # Where this mesh sits in armature space -- the point whose motion the viewer sees.
        anchor_pt = (rig.matrix_world.inverted() @ obj.matrix_world).translation

        rest_parent = rig.data.bones[parent].matrix_local
        chosen_rest_inv = rig.data.bones[chosen].matrix_local.inverted()
        i = lookup[chosen]

        worst = 0.0
        for f in range(scene.frame_start, scene.frame_end + 1):
            scene.frame_set(f)
            bpy.context.view_layer.update()
            # What the parent bone would do to the mesh, against what the exported matrix does.
            # frames[] already holds pose x bind^-1 for the chosen bone, which is exactly the
            # transform the shader applies.
            want = rig.pose.bones[parent].matrix @ rest_parent.inverted()
            got = frames[f - scene.frame_start][i]
            worst = max(worst, ((want @ anchor_pt) - (got @ anchor_pt)).length)
        worst_cm = worst * 100
        print("<<< rigid bind %s: %r -> %s reproduces the parent to %.4f cm"
              % (obj_name, parent, chosen, worst_cm))
        if worst_cm > 0.5:
            problems.append("%s: riding %s puts it %.2f cm from where %r would"
                            % (obj_name, chosen, worst_cm, parent))
        _ = chosen_rest_inv                    # kept for clarity; frames[] already folds it in

    if problems:
        raise SystemExit("<<< INVALID rigid bind:\n    " + "\n    ".join(problems))


def validate(all_batches, bone_count):
    """Refuses to write an asset that is visibly broken.

    This exists because the bug it catches shipped: dangling UV references produced coordinates like
    -6.6e36 on most of her body, and the only symptom anybody noticed was that her eyes had
    disappeared. Wrong texels on a skin map still look like skin. A sanity check at export time costs
    nothing and turns that into a loud failure here rather than a silent one on the device.
    """
    import math
    problems = []
    for name, data in all_batches:
        bad_uv = bad_pos = bad_slot = 0
        for co, nrm, uv, inf in data["verts"]:
            if not all(math.isfinite(v) for v in uv) or max(abs(uv[0]), abs(uv[1])) > 8.0:
                bad_uv += 1
            if not all(math.isfinite(v) for v in co) or max(abs(v) for v in co) > 10.0:
                bad_pos += 1
            for w, slot in inf:
                if slot < 0 or slot >= bone_count:
                    bad_slot += 1
        n = max(1, len(data["verts"]))
        if bad_uv:
            problems.append("%s: %d/%d UVs out of range" % (name, bad_uv, n))
        if bad_pos:
            problems.append("%s: %d/%d positions out of range" % (name, bad_pos, n))
        if bad_slot:
            problems.append("%s: %d bone slots out of range" % (name, bad_slot))
        if not data["tris"]:
            problems.append("%s: no triangles" % name)
    # IS EVERY PART STILL ATTACHED TO HER? Each batch carries its own object transform, so one
    # mesh can drift away from the rest without a single number going out of range. That shipped
    # once: the hair was unlinked from the view layer and reset to scale 1.0 mid-edit, and the
    # export happily wrote a .pskn with her hair lying on the floor a metre to her left while every
    # other check passed. Long hair legitimately hangs past the skin, so the test is generous --
    # it only objects when a batch has no overlap with the body at all.
    def box(verts):
        lo = [min(v[i] for v, _, _, _ in verts) for i in range(3)]
        hi = [max(v[i] for v, _, _, _ in verts) for i in range(3)]
        return lo, hi

    skin = [d for n, d in all_batches if n.startswith("Skin_")]
    if skin:
        merged = [v for d in skin for v in d["verts"]]
        lo, hi = box(merged)
        for name, data in all_batches:
            if name.startswith("Skin_") or not data["verts"]:
                continue
            blo, bhi = box(data["verts"])
            gap = max(max(blo[i] - hi[i], lo[i] - bhi[i], 0.0) for i in range(3))
            if gap > DETACHED_LIMIT:
                problems.append(
                    "%s is %.3f away from the body (bbox x %.3f..%.3f z %.3f..%.3f); its object "
                    "transform is probably wrong" % (name, gap, blo[0], bhi[0], blo[2], bhi[2]))

    if problems:
        for p in problems:
            print("<<< INVALID", p)
        raise SystemExit("export aborted: %d problem(s); see above" % len(problems))
    print("<<< validated %d batches: UVs, positions and bone slots all in range" % len(all_batches))


def main():
    global MESHES
    MESHES = idle.character_meshes(TEXTURES)
    print("<<< character meshes:", MESHES)
    # GEOMETRY FIRST, BEFORE clean() TOUCHES THE SCENE. `Hair` is bone-parented and carries
    # ARMATURE, VERTEX_WEIGHT_EDIT, VERTEX_WEIGHT_MIX, CLOTH and a second ARMATURE. Its object
    # transform alone does NOT put it on her head -- the modifier stack does -- so once clean()
    # strips the cloth for the bake, the evaluated mesh collapses back to the raw mesh data and the
    # hair gathers a metre off her body at a third of its size. Every other mesh is object-parented
    # with a single armature modifier and is indifferent to the order.
    #
    # Influences come back keyed by bone NAME here, because slot numbering does not exist until the
    # animation has been baked and pruned; remap_influences does that afterwards.
    all_batches = []
    for name in MESHES:
        for material, data in gather_mesh(name).items():
            all_batches.append((material, data))

    build_animation()
    bones = collect_bones()
    frames = skin_matrices(bones)
    order, bone_index, moving = prune(bones, frames)
    # 48 bytes per bone in std140 against the 16384-byte uniform block ES 3.0 guarantees.
    if len(order) > 320:
        print("<<< WARNING %d bone slots exceeds what a guaranteed 16 KB uniform block holds"
              % len(order))

    # Re-index the per-frame rows onto the pruned slot order.
    lookup = {name: i for i, name in enumerate(bones)}
    packed = [[row[lookup[name]] for name in moving] for row in frames]

    verify_rigid_binds(bones, frames)
    remap_influences(all_batches, bone_index)
    validate(all_batches, len(order))
    total_v = sum(len(d["verts"]) for _, d in all_batches)
    total_t = sum(len(d["tris"]) for _, d in all_batches) // 3
    print("<<< batches %d  vertices %d  triangles %d" % (len(all_batches), total_v, total_t))
    print("<<< clips: %d intro frames + %d loop frames at %d fps" % (idle.INTRO, idle.LOOP, idle.FPS))

    # The join is only seamless if the last intro frame really does land on the loop. Measured
    # rather than assumed: the offset is supposed to have decayed to nothing by then.
    a = packed[idle.INTRO - 1]
    b = packed[idle.INTRO + (idle.INTRO - 1)] if len(packed) > 2 * idle.INTRO else None
    if b is not None:
        worst = max(abs(a[i][r][c] - b[i][r][c])
                    for i in range(len(a)) for r in range(3) for c in range(4))
        print("<<< intro/loop join: worst matrix element differs by %.6f" % worst)
        if worst > 0.02:
            print("<<< WARNING the intro does not land on the loop; the handover will be visible")

    write_packed(os.path.join(OUT_DIR, "Aether.pskn"), order, packed, all_batches)

    # Materials are rewired only now, after the mesh has been gathered: it replaces node trees
    # rather than materials, so slot indices are untouched, but there is no reason to do it earlier.
    if "--fbx" in sys.argv:
        rewire_materials(os.path.join(OUT_DIR, "aether_tex"))
        export_fbx(os.path.join(FBX_DIR, "Aether_Idle.fbx"))
    print("<<< DONE")


def write_packed(path, order, packed, all_batches):
    swap = Matrix(((1, 0, 0, 0), (0, 0, 1, 0), (0, -1, 0, 0), (0, 0, 0, 1)))
    swap_inv = swap.inverted()
    with open(path, "wb") as f:
        f.write(MAGIC)
        # v2 carries two clips: the arrival, played once, then the loop. They are stored as one
        # contiguous run of frames because the intro shares the loop's phases -- see intro_pose.
        f.write(struct.pack("<IIIIfI", VERSION, len(order), idle.INTRO, idle.LOOP,
                            float(idle.FPS), len(all_batches)))
        for name, data in all_batches:
            texture, cutout = TEXTURES[name]
            raw_name = name.encode("utf-8")
            raw_tex = ("aether_tex/" + texture).encode("utf-8")
            f.write(struct.pack("<H", len(raw_name)) + raw_name)
            f.write(struct.pack("<H", len(raw_tex)) + raw_tex)
            f.write(struct.pack("<B", 1 if cutout else 0))
            f.write(struct.pack("<I", len(data["verts"])))
            f.write(struct.pack("<I", len(data["tris"])))
            for co, nrm, uv, inf in data["verts"]:
                f.write(struct.pack("<8f",
                                    co[0], co[2], -co[1],
                                    nrm[0], nrm[2], -nrm[1],
                                    uv[0], 1.0 - uv[1]))
                for _, slot in inf:
                    f.write(struct.pack("<B", slot))
                for weight, _ in inf:
                    f.write(struct.pack("<B", max(0, min(255, int(round(weight * 255.0))))))
            for i in data["tris"]:
                f.write(struct.pack("<I", i))
        for row in packed:
            f.write(struct.pack("<12f", 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0))
            for m in row:
                c = swap @ m @ swap_inv
                f.write(struct.pack("<12f", *(c[r][k] for r in range(3) for k in range(4))))
    print("<<< wrote", path, os.path.getsize(path), "bytes")


main()
