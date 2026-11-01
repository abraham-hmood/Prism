"""Build the Prism zombie from MPFB2's human mesh.

No character geometry is made from spheres, cylinders or discs.  The body comes from
MPFB2's MakeHuman basemesh and default rig.  Clothing is cut from that same surface,
offset, cloth-simulated, then detailed with attached garment geometry (pockets, plackets,
cuffs, collar, belt).  Boots are MPFB's shoes asset.  Eyes and teeth are MPFB's bodypart
assets, anchored to the skull landmarks.

Run headless:
  "C:/Program Files/Blender Foundation/Blender 5.2/blender.exe" -b \
      --factory-startup --python tools/zombie/build.py -- --seed 7 --stage full

Stages:
  geom  - build the character and clothing, render a clay preview (fast)
  full  - geom + procedural materials, 2048 bakes, turnaround, GLB
"""

import bpy
import bmesh
import math
import os
import random
import sys
import time
from mathutils import Vector, Matrix, Euler, Quaternion, noise

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

MPFB_MODULE = "bl_ext.blender_org.mpfb"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "tools", "zombie", "out")
PREVIEWS = os.path.join(ROOT, "tools", "zombie", "previews")
TEXTURES = os.path.join(OUT, "textures")
ASSET_ZIP = os.path.join(ROOT, "tools", "zombie", "assets", "makehuman_system_assets_cc0.zip")

for path in (OUT, PREVIEWS, TEXTURES):
    os.makedirs(path, exist_ok=True)


def log(*args):
    print("[ZOMBIE]", *args, flush=True)


def parse_args():
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--stage", choices=("geom", "full"), default="geom")
    parser.add_argument("--variant", choices=("male", "female"), default="male")
    parser.add_argument("--pose", choices=("stand", "crawl"), default="stand")
    parser.add_argument("--no-bake", action="store_true")
    parser.add_argument("--samples", type=int, default=48)
    parser.add_argument("--no-render", action="store_true")
    parser.add_argument("--no-cloth", action="store_true")
    parser.add_argument("--simple", action="store_true")
    parser.add_argument("--bake-samples", type=int, default=12)
    parser.add_argument("--bake-res", type=int, default=0)
    parser.add_argument("--no-anim", dest="anim", action="store_false", default=True)
    parser.set_defaults(anim=True)
    return parser.parse_args(argv)


def enable_mpfb():
    bpy.ops.preferences.addon_enable(module=MPFB_MODULE)
    from bl_ext.blender_org.mpfb.services.humanservice import HumanService
    from bl_ext.blender_org.mpfb.services.locationservice import LocationService
    return HumanService, LocationService


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.unit_settings.system = 'METRIC'
    scene.unit_settings.scale_length = 1.0
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.use_denoising = True
    world = bpy.data.worlds.new("ZombieWorld")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.018, 0.019, 0.021, 1.0)
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.35
    scene.world = world
    return scene


def activate(obj):
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def apply_modifier(obj, name):
    activate(obj)
    bpy.ops.object.modifier_apply(modifier=name)


def triangle_count(objects):
    total = 0
    for obj in objects:
        if obj.type != 'MESH':
            continue
        me = obj.data
        me.calc_loop_triangles()
        total += len(me.loop_triangles)
    return total


def world_bbox(obj):
    pts = [obj.matrix_world @ Vector(c) for c in obj.bound_box]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return lo, hi


def delete_faces(obj, predicate, name="cut"):
    """Delete mesh faces whose center matches predicate, and return the new boundary edges."""
    activate(obj)
    me = obj.data
    bm = bmesh.new()
    bm.from_mesh(me)
    doomed = [f for f in bm.faces if predicate(f.calc_center_median().copy())]
    if not doomed:
        bm.free()
        log("delete_faces:", name, "matched 0 faces")
        return 0
    bmesh.ops.delete(bm, geom=doomed, context='FACES')
    loose = [v for v in bm.verts if not v.link_faces]
    if loose:
        bmesh.ops.delete(bm, geom=loose, context='VERTS')
    bm.to_mesh(me)
    bm.free()
    me.update()
    log("delete_faces:", name, "removed", len(doomed), "faces")
    return len(doomed)


def boundary_edges(obj):
    activate(obj)
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    edges = [e for e in bm.edges if e.is_boundary]
    bm.free()
    return edges


def nearest_bone(rig, point):
    best = None
    bestd = 1e9
    for bone in rig.data.bones:
        if bone.name.startswith(("toe", "finger", "metacarpal", "oris", "levator", "oculi", "temporalis", "risorius", "special", "tongue", "eye")):
            continue
        mid = (bone.head_local + bone.tail_local) * 0.5
        d = (mid - point).length
        if d < bestd:
            bestd = d
            best = bone.name
    return best


# ----------------------------------------------------------------------------------
# Human
# ----------------------------------------------------------------------------------

def make_human(seed, variant):
    HumanService, LocationService = enable_mpfb()
    rng = random.Random(seed)
    female = variant == "female"
    macro = {
        "gender": 0.0 if female else 1.0,
        "age": 0.88 + rng.random() * 0.10,
        "muscle": 0.22 + rng.random() * 0.10,
        "weight": 0.13 + rng.random() * 0.05,
        "height": 0.62 if not female else 0.58,
        "proportions": 0.50,
        "cupsize": 0.45 if female else 0.5,
        "firmness": 0.5,
        "race": {"african": 0.0, "asian": 0.0, "caucasian": 1.0},
    }
    # detailed_helpers must stay on: MPFB's rig fit places bones from the "joint-*" helper
    # groups.  mask_helpers is off because we strip the islands instead of masking them.
    body = HumanService.create_human(mask_helpers=False, detailed_helpers=True,
                                     extra_vertex_groups=True, feet_on_ground=True,
                                     scale=0.1, macro_detail_dict=macro)
    body.name = "Zombie.body"
    # MakeHuman's target library carries real facial anatomy (bone structure, hollows,
    # ageing).  Using it beats pushing gaussians around: the skull shape is authored, not
    # guessed.  All targets are then baked into the mesh before anything is fitted to it.
    apply_face_targets(body, LocationService)
    if body.data.shape_keys:
        activate(body)
        bpy.ops.object.shape_key_remove(all=True, apply_mix=True)
        log("macro + face targets baked", round(max(v.co.z for v in body.data.vertices), 3))
    data_dir = LocationService.get_user_data()
    # Assets are fitted against the untouched base buffer: .mhclo files index original
    # vertex numbers, so helper islands can only be removed after this point.
    eyes = HumanService.add_mhclo_asset(os.path.join(data_dir, "eyes", "high-poly", "high-poly.mhclo"),
                                        body, asset_type="Eyes", subdiv_levels=0,
                                        material_type="PROCEDURAL_EYES")
    teeth = HumanService.add_mhclo_asset(os.path.join(data_dir, "teeth", "teeth_base", "teeth_base.mhclo"),
                                         body, asset_type="Teeth", subdiv_levels=0,
                                         material_type="MAKESKIN")
    eyes.name = "Zombie.eyes"
    teeth.name = "Zombie.teeth"
    eyes.location = (0, 0, 0)
    teeth.location = (0, 0, 0)
    boot_path = os.path.join(data_dir, "clothes", "shoes03", "shoes03.mhclo")
    # MHCLO clothing carries a delete group that removes the body surface underneath.
    # For shoes that means the feet; keep a pristine copy and restore it afterwards, since
    # the boots are a separate export object anyway.
    pristine = body.data.copy()
    boots = HumanService.add_mhclo_asset(boot_path, body, asset_type="Clothes", subdiv_levels=0,
                                         material_type="MAKESKIN")
    boots.name = "Zombie.boots"
    boots.location = (0, 0, 0)
    body.data = pristine

    rig = HumanService.add_builtin_rig(body, "default", import_weights=True)
    rig.name = "Zombie.rig"
    rig.data.name = "Zombie.rig"
    # MPFB bakes helper islands into the same mesh: a full skirt/hair/cube assembly used to
    # mask clothing regions.  They are normally hidden by mask modifiers, but every garment
    # extraction and cloth collision would otherwise run over them.  Rig fitting and asset
    # fitting both index the original buffer, so the strip happens after both.
    strip_mpfb_helpers(body)
    log("body created", len(body.data.vertices), "verts", len(body.data.polygons), "faces")
    log("rig bones", len(rig.data.bones))

    # MPFB's tooth fitting is unreliable with an aged macro body: detach and anchor by bbox.
    mouth_z = _bone_head(rig, "jaw").z - 0.048
    mouth_y = face_surface_y(body, mouth_z)
    for obj, width, center in (
            # 0.080 spans both globes (63 mm pupils + a 24 mm eyeball each).  A narrower
            # box shrank the eyes until the socket cavity showed around them and the face
            # read as a pair of goggles.
            (eyes, 0.080, Vector((0.0, face_surface_y(body, _bone_head(rig, "eye.L").z) + 0.003,
                                  _bone_head(rig, "eye.L").z))),
            (teeth, 0.056, Vector((0.0, mouth_y + 0.014, mouth_z - 0.003)))):
        detach_asset(obj)
        normalise_asset(obj, width, center)
        if obj is eyes:
            # normalise_asset fits a fixed box, which gave either beads rattling in the
            # sockets or ping-pong balls stuck on the face.  Size the globes anatomically
            # (24.5 mm) and sink the cornea just behind the lid opening instead.
            lo, hi = world_bbox(obj)
            height = max(hi.z - lo.z, 1e-6)
            factor = 0.0245 / height
            obj.scale = [v * factor for v in obj.scale]
            bpy.context.view_layer.update()
            lo, hi = world_bbox(obj)
            surface = face_surface_y(body, (lo.z + hi.z) / 2)
            obj.location.y += (surface + 0.002) - lo.y
            bpy.context.view_layer.update()
        bind_to_bone(obj, rig, "head")

    # The body no longer needs morph targets once the assets are fitted.
    if body.data.shape_keys:
        body.shape_key_clear()
    # Remove MPFB's fit modifiers before applying the helper mask.
    for mod in list(body.modifiers):
        if mod.type != 'MASK':
            body.modifiers.remove(mod)
    for mod in list(body.modifiers):
        if mod.type == 'MASK':
            apply_modifier(body, mod.name)
    # Rebuild the armature link.
    arm = body.modifiers.new("Armature", 'ARMATURE')
    arm.object = rig
    body.parent = rig
    log("body after mask", len(body.data.vertices), "verts", len(body.data.polygons), "faces")
    return body, rig, eyes, teeth, boots


FACE_TARGETS = (
    ("head-age-incr", 1.00),
    ("head-fat-decr", 0.90),
    ("head-scale-vert-incr", 0.45),
    ("forehead-temple-decr", 0.90),
    ("forehead-trans-backward", 0.30),
    ("l-cheek-bones-incr", 0.90), ("r-cheek-bones-incr", 0.90),
    ("l-cheek-volume-decr", 0.95), ("r-cheek-volume-decr", 0.95),
    ("l-cheek-inner-decr", 0.65), ("r-cheek-inner-decr", 0.65),
    ("l-cheek-trans-down", 0.45), ("r-cheek-trans-down", 0.45),
    ("chin-bones-incr", 0.60),
    ("chin-prominent-incr", 0.40),
    ("chin-width-decr", 0.45),
    ("nose-hump-incr", 0.30),
    ("nose-curve-convex", 0.25),
    ("nose-nostrils-width-incr", 0.20),
    ("mouth-trans-backward", 0.30),
    ("mouth-upperlip-volume-decr", 0.55),
    ("mouth-lowerlip-volume-decr", 0.55),
    ("mouth-lowerlip-height-decr", 0.40),
    ("neck-scale-horiz-decr", 0.45),
    ("l-ear-scale-decr", 0.30), ("r-ear-scale-decr", 0.30),
    ("l-ear-flap-decr", 0.35), ("r-ear-flap-decr", 0.35),
    ("l-ear-rot-backward", 0.20), ("r-ear-rot-backward", 0.20),
    ("l-eye-bag-decr", 0.50), ("r-eye-bag-decr", 0.50),
    ("l-eye-scale-decr", 0.15), ("r-eye-scale-decr", 0.15),
)


def _target_index(root):
    index = {}
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith(".target.gz"):
                index[os.path.basename(f)[:-len(".target.gz")]] = os.path.join(dirpath, f)
    return index


def apply_face_targets(body, LocationService):
    from bl_ext.blender_org.mpfb.services.targetservice import TargetService
    root = LocationService.get_mpfb_data("targets")
    index = _target_index(root)
    applied, missing = 0, []
    activate(body)
    for name, weight in FACE_TARGETS:
        path = index.get(name)
        if not path:
            missing.append(name)
            continue
        TargetService.load_target(body, path, weight=weight, name=name)
        applied += 1
    log("face targets applied", applied, "missing", missing)


def strip_mpfb_helpers(body):
    """Delete MPFB's helper geometry (skirt, hair cards, joint cubes).

    Those parts are separate shells inside the same mesh.  They cannot be selected by
    vertex group weight alone: the joint-cube groups also carry weights on real body
    vertices, so a weight cut punches holes at the hips, knees and elbows.  Keep only the
    connected component that owns the most vertices; that is the actual body.
    """
    bm = bmesh.new()
    bm.from_mesh(body.data)
    bm.verts.ensure_lookup_table()
    bm.faces.ensure_lookup_table()
    unvisited = set(bm.verts)
    islands = []
    while unvisited:
        seed = unvisited.pop()
        stack = [seed]
        island = [seed]
        while stack:
            v = stack.pop()
            for e in v.link_edges:
                o = e.other_vert(v)
                if o in unvisited:
                    unvisited.remove(o)
                    island.append(o)
                    stack.append(o)
        islands.append(island)
    islands.sort(key=len, reverse=True)
    keep = islands[0]
    doomed_verts = [v for island in islands[1:] for v in island]
    if doomed_verts:
        bmesh.ops.delete(bm, geom=doomed_verts, context='VERTS')
    bm.to_mesh(body.data)
    bm.free()
    body.data.update()
    for name in ("HelperGeometry", "JointCubes"):
        vg = body.vertex_groups.get(name)
        if vg:
            body.vertex_groups.remove(vg)
    log("helper geometry stripped", len(doomed_verts), "verts in",
        len(islands) - 1, "islands removed;", len(body.data.vertices), "remain")


def _bone_head(rig, name):
    return rig.matrix_world @ rig.data.bones[name].head_local


def face_surface_y(body, z):
    """Front surface of the face at a given height, as a world Y coordinate."""
    ok, loc, _nor, _idx = body.ray_cast(Vector((0.0, -3.0, z)), Vector((0.0, 1.0, 0.0)))
    if ok:
        return loc.y
    # Fallback: the closest vertex on the front half of the skull.
    pts = [(body.matrix_world @ v.co) for v in body.data.vertices
           if abs(v.co.x) < 0.02 and abs(v.co.z - z) < 0.02]
    if pts:
        return min(p.y for p in pts)
    return -0.14


def detach_asset(obj):
    mw = obj.matrix_world.copy()
    obj.parent = None
    obj.matrix_world = mw
    obj.modifiers.clear()
    # Asset fitting stores offsets in shape keys.  Normalising the base vertices while a
    # non-zero shape key remains would restore the broken fitted position at evaluation.
    if obj.data.shape_keys:
        obj.shape_key_clear()
    activate(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def normalise_asset_box(obj, target_dims, target_center):
    me = obj.data
    xs = [v.co.x for v in me.vertices]
    ys = [v.co.y for v in me.vertices]
    zs = [v.co.z for v in me.vertices]
    size = Vector((max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs)))
    scale = Vector((target_dims[0] / max(size.x, 1e-9),
                    target_dims[1] / max(size.y, 1e-9),
                    target_dims[2] / max(size.z, 1e-9)))
    for v in me.vertices:
        v.co.x *= scale.x
        v.co.y *= scale.y
        v.co.z *= scale.z
    me.update()
    xs = [v.co.x for v in me.vertices]
    ys = [v.co.y for v in me.vertices]
    zs = [v.co.z for v in me.vertices]
    center = Vector(((max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2, (max(zs) + min(zs)) / 2))
    for v in me.vertices:
        v.co += Vector(target_center) - center
    me.update()
    log("asset box", obj.name, "dims", tuple(round(v, 3) for v in target_dims), "center", tuple(round(c, 3) for c in target_center))


def normalise_asset(obj, target_width, target_center):
    me = obj.data
    xs = [v.co.x for v in me.vertices]
    ys = [v.co.y for v in me.vertices]
    zs = [v.co.z for v in me.vertices]
    size_x = max(xs) - min(xs)
    s = target_width / max(size_x, 1e-9)
    for v in me.vertices:
        v.co *= s
    me.update()
    xs = [v.co.x for v in me.vertices]
    ys = [v.co.y for v in me.vertices]
    zs = [v.co.z for v in me.vertices]
    center = Vector(((max(xs) + min(xs)) / 2, (max(ys) + min(ys)) / 2, (max(zs) + min(zs)) / 2))
    for v in me.vertices:
        v.co += target_center - center
    me.update()
    log("asset", obj.name, "size", round(target_width, 4), "center", tuple(round(c, 3) for c in target_center))


def bind_to_bone(obj, rig, bone):
    """Rigidly skin an accessory to a single bone.

    Bone parenting in Blender anchors to the bone tail and silently shifts props that were
    placed in world space.  A vertex group with weight 1 plus an armature modifier keeps the
    prop exactly where it was built while still following the skeleton.
    """
    # matrix_world is evaluated lazily; a freshly created object still reports identity
    # until the view layer is updated, which used to leave the buttons at the origin.
    bpy.context.view_layer.update()
    world = obj.matrix_world.copy()
    for mod in list(obj.modifiers):
        if mod.type == 'ARMATURE':
            obj.modifiers.remove(mod)
    for vg in list(obj.vertex_groups):
        obj.vertex_groups.remove(vg)
    if rig.data.bones.get(bone) is None:
        bone = "root"
    vg = obj.vertex_groups.new(name=bone)
    vg.add(range(len(obj.data.vertices)), 1.0, 'REPLACE')
    obj.parent = rig
    obj.matrix_world = world
    arm = obj.modifiers.new("Armature", 'ARMATURE')
    arm.object = rig
    bpy.context.view_layer.update()


# ----------------------------------------------------------------------------------
# Body sculpt
# ----------------------------------------------------------------------------------

def gauss(p, center, radius):
    d = (p - center).length
    return math.exp(-(d * d) / (2.0 * radius * radius))


def sculpt_body(body, rig, seed):
    rng = random.Random(seed)
    activate(body)
    body.data.materials.clear()
    for mod in list(body.modifiers):
        body.modifiers.remove(mod)
    sub = body.modifiers.new("Surface", 'SUBSURF')
    sub.levels = 1
    sub.render_levels = 1
    sub.subdivision_type = 'CATMULL_CLARK'
    apply_modifier(body, sub.name)
    arm = body.modifiers.new("Armature", 'ARMATURE')
    arm.object = rig

    me = body.data
    eye_l = _bone_head(rig, "eye.L")
    eye_r = _bone_head(rig, "eye.R")
    mouth = Vector((0.0, -0.157, _bone_head(rig, "jaw").z - 0.045))
    nose = Vector((0.0, -0.180, mouth.z + 0.075))
    cheek_l = Vector((0.047, -0.148, mouth.z + 0.055))
    cheek_r = Vector((-0.047, -0.148, mouth.z + 0.055))
    temple_l = Vector((0.082, -0.105, mouth.z + 0.10))
    temple_r = Vector((-0.082, -0.105, mouth.z + 0.10))
    scalp = _bone_head(rig, "head").z + 0.10
    elbow_l = _bone_head(rig, "lowerarm01.L")
    elbow_r = _bone_head(rig, "lowerarm01.R")

    me.calc_normals_split() if hasattr(me, "calc_normals_split") else None
    normals = [v.normal.copy() for v in me.vertices]
    disp = [0.0] * len(me.vertices)
    for i, v in enumerate(me.vertices):
        p = v.co
        d = 0.0
        # The skull comes from MakeHuman face targets; the sculpt only adds what skin does.
        for eye in (eye_l, eye_r):
            if p.y < -0.06:
                d -= 0.0085 * gauss(p, eye, 0.026)
                rim_r = (p - eye).length - 0.024
                d += 0.0028 * math.exp(-(rim_r * rim_r) / (2 * 0.006 ** 2)) if p.y < -0.10 else 0
                # crepe skin under the eye
                d -= 0.0016 * gauss(p, eye + Vector((0, -0.004, -0.018)), 0.012)
        # Brow ridge, cheekbones and the hollows between them: the three shapes that stop a
        # bald skull reading as an egg.
        for sx in (-1, 1):
            d += 0.0042 * gauss(p, Vector((sx * 0.030, -0.118, eye_l.z + 0.031)), 0.024)
            d += 0.0036 * gauss(p, Vector((sx * 0.052, -0.132, mouth.z + 0.048)), 0.020)
            d -= 0.0050 * gauss(p, Vector((sx * 0.058, -0.100, mouth.z - 0.022)), 0.022)
        # Shrinkage creases over the cheekbones and temples.
        d -= 0.0100 * gauss(p, cheek_l, 0.034)
        d -= 0.0100 * gauss(p, cheek_r, 0.034)
        d -= 0.0075 * gauss(p, temple_l, 0.036)
        d -= 0.0075 * gauss(p, temple_r, 0.036)
        # Lips pulled back off the teeth.
        if p.y < -0.10 and (p - mouth).length < 0.055:
            d += 0.0038 * gauss(p, mouth, 0.024)
        # Nasolabial folds.
        for sx in (-1, 1):
            d -= 0.0018 * gauss(p, Vector((sx * 0.028, -0.145, mouth.z + 0.020)), 0.011)
        # Neck tendons and hollow above the collarbone.
        d -= 0.0022 * gauss(p, Vector((0.040, -0.055, mouth.z - 0.13)), 0.024)
        d -= 0.0022 * gauss(p, Vector((-0.040, -0.055, mouth.z - 0.13)), 0.024)
        d -= 0.0025 * gauss(p, Vector((0.0, -0.075, mouth.z - 0.20)), 0.030)
        # Cracked scalp: ridged noise pressed in.
        if p.z > scalp:
            n = noise.noise_vector(p * 34.0, noise_basis='PERLIN_ORIGINAL')
            cracks = abs(n.x + n.y * 0.5) ** 0.35
            d -= 0.0022 * cracks
        # Pores and wrinkles over the face.
        if p.z > mouth.z - 0.16:
            n = noise.noise_vector(p * 90.0, noise_basis='PERLIN_ORIGINAL')
            d += (n.x * 0.0008 + n.y * 0.0005)
        # Rotted forearms and hands: irregular surface loss.
        for elbow, side in ((elbow_l, 1), (elbow_r, -1)):
            if p.z < elbow.z + 0.09 and side * p.x > 0.20:
                n = noise.noise_vector(p * 55.0, noise_basis='PERLIN_ORIGINAL')
                d -= 0.0035 * (0.5 + 0.5 * n.x)
                d += 0.0015 * n.z
        # Whole-body mummified micro-variation.
        n = noise.noise_vector(p * 120.0, noise_basis='PERLIN_ORIGINAL')
        d += n.x * 0.00035
        disp[i] = d

    # Two light smoothing passes so the displacement stays a surface, not spikes.
    for _ in range(2):
        neigh = [[] for _ in me.vertices]
        for e in me.edges:
            a, b = e.vertices
            neigh[a].append(b)
            neigh[b].append(a)
        new = disp[:]
        for i, ns in enumerate(neigh):
            if ns:
                avg = sum(disp[j] for j in ns) / len(ns)
                new[i] = disp[i] * 0.6 + avg * 0.4
        disp = new

    for i, v in enumerate(me.vertices):
        v.co += normals[i] * disp[i]
    me.update()

    # Open the mouth and both eyes; keep the cuts on the front of the face.
    # A torn slit rather than an open grin: the reference faces keep their mouths mostly
    # closed, with teeth showing through split lips.
    delete_faces(body, lambda c: c.y < -0.105 and
                 ((c.x / 0.0230) ** 2 + ((c.z - mouth.z) / 0.0092) ** 2) < 1.0, "mouth")
    for eye in (eye_l, eye_r):
        # A small almond opening; larger cuts leave square gaps at the corners once the
        # low-poly lid boundary is exposed around the eyeball.
        delete_faces(body, lambda c, e=eye: c.y < -0.10 and
                     (((c.x - e.x) / 0.0118) ** 2 + ((c.z - e.z) / 0.0058) ** 2) < 1.0,
                     "eye " + ("L" if eye.x > 0 else "R"))

    cavity = make_mouth_cavity(body, mouth)
    make_eye_sockets(body, eye_l, eye_r)

    # Colour mask consumed by the skin shader and baked into the albedo.
    wound = body.data.color_attributes.new(name="wound", type='FLOAT_COLOR', domain='POINT')
    dirt = body.data.color_attributes.new(name="grime", type='FLOAT_COLOR', domain='POINT')
    blood = body.data.color_attributes.new(name="blood", type='FLOAT_COLOR', domain='POINT')
    for i, v in enumerate(me.vertices):
        p = v.co
        w = 0.0
        # Hands and forearms are open rotted tissue.
        for elbow, side in ((elbow_l, 1), (elbow_r, -1)):
            if side * p.x > 0.22 and p.z < elbow.z + 0.10:
                w = max(w, 0.30 + 0.45 * min(1.0, (elbow.z + 0.10 - p.z) / 0.22))
        if 0.43 < p.z < 0.66 and p.y < -0.015:
            w = max(w, 0.75 * gauss(p, Vector((0.16 if p.x > 0 else -0.16, -0.08, 0.565)), 0.11))
        if p.z > mouth.z - 0.07 and p.y < -0.10:
            w = max(w, 0.16 * gauss(p, mouth + Vector((0, 0, 0.004)), 0.024))
        w = min(1.0, w + abs(noise.noise_vector(p * 30.0).x) * 0.18)
        # Masks are replicated across RGB so the shader's Fac output equals the mask.
        wound.data[i].color = (w, w, w, 1.0)
        g = 0.35 + 0.25 * abs(noise.noise_vector(p * 12.0).x)
        if p.z < 0.28:
            g = 0.85
        if p.z > scalp:
            g = 0.55 + 0.3 * abs(noise.noise_vector(p * 28.0).x)
        # Sockets and the underside of the brow are the darkest part of any face.
        for e in (eye_l, eye_r):
            if p.y < -0.075:
                g = max(g, 0.72 * gauss(p, e + Vector((0, 0, -0.003)), 0.030))
        g = min(1.0, g)
        dirt.data[i].color = (g, g, g, 1.0)
        # Dried blood: running from the mouth onto the chin and throat, soaked into the
        # chest wound, and coating the ruined forearms.
        b = 0.0
        b = max(b, 1.8 * gauss(p, mouth + Vector((0, 0.004, 0.0)), 0.034))
        b = max(b, 1.1 * gauss(p, Vector((0.0, -0.142, mouth.z - 0.045)), 0.038))
        b = max(b, 0.8 * gauss(p, Vector((0.0, -0.132, mouth.z - 0.105)), 0.055))
        # A single asymmetric smear on one cheek plus a nosebleed trail; mirrored blobs read
        # as face paint, not injury.
        b = max(b, 0.60 * gauss(p, Vector((0.036, -0.132, mouth.z + 0.028)), 0.024))
        for zz in (mouth.z + 0.055, mouth.z + 0.075, mouth.z + 0.095):
            b = max(b, 0.55 * gauss(p, Vector((-0.008, -0.150, zz)), 0.010))
        for elbow, side in ((elbow_l, 1), (elbow_r, -1)):
            if side * p.x > 0.24 and p.z < elbow.z + 0.08:
                b = max(b, 0.75)
        b = max(b, 0.9 * gauss(p, Vector((0.175 if p.x > 0 else -0.175, -0.10, 0.60)), 0.075))
        b *= 0.55 + 0.65 * abs(noise.noise_vector(p * 18.0).x)
        b = min(1.0, b)
        blood.data[i].color = (b, b, b, 1.0)
        if i in cavity:
            # Inside of the mouth: dark tissue, no light reaches it.
            wound.data[i].color = (1.0, 1.0, 1.0, 1.0)
            blood.data[i].color = (1.0, 1.0, 1.0, 1.0)
            dirt.data[i].color = (1.0, 1.0, 1.0, 1.0)
    log("body sculpted", len(me.vertices), "verts")


def make_mouth_cavity(body, mouth):
    """Extrude the mouth opening inward to a dark, attached oral cavity."""
    activate(body)
    me = body.data
    bm = bmesh.new()
    bm.from_mesh(me)
    edges = [e for e in bm.edges if e.is_boundary and abs(e.verts[0].co.z - mouth.z) < 0.05]
    if not edges:
        bm.free()
        log("mouth cavity: no boundary")
        return
    inner = Vector((0.0, -0.120, mouth.z - 0.002))
    ret = bmesh.ops.extrude_edge_only(bm, edges=edges)
    geom = [g for g in ret["geom"] if isinstance(g, bmesh.types.BMEdge)]
    verts = [v for v in ret["geom"] if isinstance(v, bmesh.types.BMVert)]
    for v in verts:
        v.co = v.co.lerp(inner, 0.78)
    # Cap the innermost loop.
    vs = list({v for e in geom for v in e.verts})
    if len(vs) > 2:
        try:
            bmesh.ops.contextual_create(bm, geom=vs)
        except Exception as exc:
            log("mouth cap skipped", exc)
    bm.verts.index_update()
    inside = {v.index for v in verts}
    bm.to_mesh(me)
    bm.free()
    me.update()
    log("mouth cavity built", len(inside))
    return inside


def make_eye_sockets(body, eye_l, eye_r):
    """Sink the lid ring so the eyeballs sit deep, and thin the lids over them."""
    me = body.data
    for v in me.vertices:
        p = v.co
        for e in (eye_l, eye_r):
            d = (Vector((p.x, 0, p.z)) - Vector((e.x, 0, e.z))).length
            if p.y < -0.10 and 0.012 < d < 0.034:
                v.co.y += 0.0026 * (1.0 - abs(d - 0.022) / 0.014)
    me.update()


# ----------------------------------------------------------------------------------
# Garments
# ----------------------------------------------------------------------------------

def extract_surface(body, name, predicate):
    """Copy the faces of a body that satisfy predicate into a new garment mesh."""
    activate(body)
    dup = body.copy()
    dup.data = body.data.copy()
    dup.name = name
    bpy.context.scene.collection.objects.link(dup)
    dup.modifiers.clear()
    dup.parent = None
    bm = bmesh.new()
    bm.from_mesh(dup.data)
    keep = set()
    for f in bm.faces:
        if predicate(f.calc_center_median().copy()):
            keep.add(f.index)
    doomed = [f for f in bm.faces if f.index not in keep]
    bmesh.ops.delete(bm, geom=doomed, context='FACES')
    loose = [v for v in bm.verts if not v.link_faces]
    if loose:
        bmesh.ops.delete(bm, geom=loose, context='VERTS')
    bm.to_mesh(dup.data)
    bm.free()
    dup.data.update()
    log(name, "extracted", len(dup.data.vertices), "verts", len(dup.data.polygons), "faces")
    return dup


def offset_surface(obj, base, wrinkle=0.006, seed=0):
    """Lift a garment off the body and give it cloth wrinkles.

    Vertex normals read straight after a bmesh write are not reliable, and a failed lift
    leaves the garment exactly coplanar with the skin: the two surfaces then fight for every
    pixel.  Displace modifiers always use freshly computed normals.
    """
    activate(obj)
    tex = bpy.data.textures.new("wrinkle_" + obj.name, 'CLOUDS')
    tex.noise_scale = 0.115
    tex.noise_depth = 2
    tex.noise_basis = 'BLENDER_ORIGINAL'
    wr = obj.modifiers.new("Wrinkles", 'DISPLACE')
    wr.texture = tex
    wr.texture_coords = 'LOCAL'
    wr.direction = 'NORMAL'
    wr.mid_level = 0.0
    wr.strength = wrinkle * 0.55
    apply_modifier(obj, wr.name)
    lift = obj.modifiers.new("Lift", 'DISPLACE')
    lift.direction = 'NORMAL'
    lift.mid_level = 0.0
    lift.strength = base
    apply_modifier(obj, lift.name)
    log("offset", obj.name, base, "wrinkle", wrinkle)


def cloth_sim(obj, body, pin_above, frames=26, pressure=1.2, seed=0):
    me = obj.data
    activate(obj)
    # Pin group.
    for vg in list(obj.vertex_groups):
        obj.vertex_groups.remove(vg)
    pin = obj.vertex_groups.new(name="cloth_pin")
    idx = [v.index for v in me.vertices if v.co.z > pin_above]
    if not idx:
        raise RuntimeError("cloth_sim: pin group is empty for " + obj.name)
    pin.add(idx, 1.0, 'REPLACE')
    log("cloth pins", obj.name, len(idx))

    # Body collision.
    if not any(m.type == 'COLLISION' for m in body.modifiers):
        col = body.modifiers.new("ClothCollision", 'COLLISION')
        col.settings.thickness_outer = 0.004
        col.settings.thickness_inner = 0.002
        col.settings.cloth_friction = 8.0

    mod = obj.modifiers.new("Cloth", 'CLOTH')
    s = mod.settings
    s.quality = 7
    s.mass = 0.30
    s.air_damping = 1.2
    s.tension_stiffness = 22.0
    s.compression_stiffness = 22.0
    s.shear_stiffness = 12.0
    s.bending_stiffness = 2.0
    s.vertex_group_mass = pin.name
    s.pin_stiffness = 2.0
    s.use_pressure = pressure > 0
    s.pressure_factor = pressure
    s.use_dynamic_mesh = False
    cs = mod.collision_settings
    cs.use_collision = True
    cs.distance_min = 0.006
    cs.collision_quality = 3
    cs.use_self_collision = False

    scene = bpy.context.scene
    scene.frame_start = 1
    scene.frame_end = frames
    scene.frame_set(1)
    bpy.context.view_layer.update()
    scene.frame_set(frames)
    bpy.context.view_layer.update()
    deps = bpy.context.evaluated_depsgraph_get()
    deps.update()
    apply_modifier(obj, mod.name)
    # Relax the simulated result slightly, then thicken it.
    smooth = obj.modifiers.new("Relax", 'SMOOTH')
    smooth.factor = 0.25
    smooth.iterations = 2
    apply_modifier(obj, smooth.name)
    solid = obj.modifiers.new("ClothThickness", 'SOLIDIFY')
    solid.thickness = 0.0045
    solid.offset = 0.0
    apply_modifier(obj, solid.name)
    for vg in list(obj.vertex_groups):
        obj.vertex_groups.remove(vg)
    log("cloth applied", obj.name, len(me.vertices), "verts")


def cut_shirt_openings(shirt, neck_z, hem_z):
    def cut(c):
        # Only a neck opening.  The front stays closed so the placket and buttons read.
        if c.z > neck_z - 0.055 and math.hypot(c.x, c.y + 0.012) < 0.062:
            return True
        return False
    delete_faces(shirt, cut, "shirt openings")


def surface_panel(source, name, keep, offset=0.005, thickness=0.003):
    """Lift a patch of an existing garment surface off as an attached panel.

    Pockets, flaps, plackets and collars are all cut straight out of the garment they sit
    on.  Building them as free sheets and projecting them was fragile: a single bad
    projection leaves a large flat plane standing off the chest.
    """
    activate(source)
    dup = source.copy()
    dup.data = source.data.copy()
    dup.name = name
    bpy.context.scene.collection.objects.link(dup)
    dup.modifiers.clear()
    dup.parent = None
    bm = bmesh.new()
    bm.from_mesh(dup.data)
    doomed = [f for f in bm.faces if not keep(f.calc_center_median().copy())]
    if doomed:
        bmesh.ops.delete(bm, geom=doomed, context='FACES')
    loose = [v for v in bm.verts if not v.link_faces]
    if loose:
        bmesh.ops.delete(bm, geom=loose, context='VERTS')
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(dup.data)
    bm.free()
    dup.data.update()
    # Lift the panel with a modifier rather than reading vertex normals in Python: normals
    # are stale straight after a bmesh write, which flattened the panels into the garment
    # and produced the shredded z-fighting seen in the renders.
    if offset:
        disp = dup.modifiers.new("Lift", 'DISPLACE')
        disp.direction = 'NORMAL'
        disp.mid_level = 0.0
        disp.strength = offset
        apply_modifier(dup, disp.name)
    if thickness > 0:
        mod = dup.modifiers.new("Thickness", 'SOLIDIFY')
        mod.thickness = thickness
        mod.offset = 0.0
        apply_modifier(dup, mod.name)
    return dup


def straighten_boundary(obj, z_lo, z_hi, target_z=None):
    """Flatten a jagged cut edge (hem, cuff) onto one height so it reads as a sewn edge."""
    activate(obj)
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    edges = [e for e in bm.edges if e.is_boundary
             and z_lo < e.verts[0].co.z < z_hi and z_lo < e.verts[1].co.z < z_hi]
    if not edges:
        bm.free()
        return
    z = target_z if target_z is not None else sum(e.verts[0].co.z for e in edges) / len(edges)
    verts = {v for e in edges for v in e.verts}
    for v in verts:
        v.co.z = z
    bm.to_mesh(obj.data)
    bm.free()
    obj.data.update()


def join_objects(target, others):
    activate(target)
    for o in others:
        o.select_set(True)
    bpy.context.view_layer.objects.active = target
    if others:
        bpy.ops.object.join()
    return target


def add_cylinder(name, center, radius, depth, axis='Y', segments=16):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=segments,
                          radius1=radius, radius2=radius, depth=depth)
    if axis == 'Y':
        bmesh.ops.rotate(bm, verts=bm.verts, cent=(0, 0, 0), matrix=Matrix.Rotation(math.radians(90), 3, 'X'))
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    ob.location = center
    return ob


def add_shirt_details(shirt, seed, hem_z, neck_z, shoulder_z, rig):
    rng = random.Random(seed)
    parts = []
    chest_z = _bone_head(rig, "spine01").z - 0.075
    front = lambda c: c.y < -0.02

    # Button placket: a narrow raised strip down the centre front.
    parts.append(surface_panel(
        shirt, "Shirt.placket",
        lambda c: front(c) and abs(c.x) < 0.028 and hem_z + 0.005 < c.z < neck_z - 0.020,
        offset=0.004, thickness=0.003))

    # Chest pockets and their flaps.
    for side in (-1, 1):
        cx = side * 0.092
        parts.append(surface_panel(
            shirt, "Shirt.pocket",
            lambda c, cx=cx: front(c) and abs(c.x - cx) < 0.048 and
            chest_z - 0.070 < c.z < chest_z + 0.010,
            offset=0.006, thickness=0.004))
        parts.append(surface_panel(
            shirt, "Shirt.flap",
            lambda c, cx=cx: front(c) and abs(c.x - cx) < 0.052 and
            chest_z + 0.010 < c.z < chest_z + 0.040,
            offset=0.011, thickness=0.003))

    # Epaulettes ride the top of each shoulder.
    for side in (-1, 1):
        parts.append(surface_panel(
            shirt, "Shirt.epaulette",
            lambda c, side=side: side * c.x > 0.045 and abs(c.x) < 0.175 and
            abs(c.y) < 0.055 and c.z > shoulder_z - 0.025,
            offset=0.007, thickness=0.004))

    # Collar: a band cut from the neckline itself, so its edge follows the opening.
    parts.append(surface_panel(
        shirt, "Shirt.collar",
        lambda c: c.z > neck_z - 0.020,
        offset=0.006, thickness=0.0035))

    join_objects(shirt, parts)
    log("shirt details joined", len(shirt.data.polygons), "faces")


def add_sleeve_rolls(shirt):
    bm = bmesh.new()
    bm.from_mesh(shirt.data)
    edges = [e for e in bm.edges if e.is_boundary and 1.25 < e.verts[0].co.z < 1.44 and abs(e.verts[0].co.x) > 0.30]
    if edges:
        # Build three concentric rolled bands.
        cur = edges
        for step in range(3):
            ret = bmesh.ops.extrude_edge_only(bm, edges=cur)
            verts = [g for g in ret["geom"] if isinstance(g, bmesh.types.BMVert)]
            for v in verts:
                center = Vector((0, v.co.y, v.co.z))
                out = (v.co - center)
                if out.length > 1e-6:
                    out.normalize()
                v.co += out * (0.008 - step * 0.003)
                v.co.z += 0.006 - step * 0.004
            cur = [g for g in ret["geom"] if isinstance(g, bmesh.types.BMEdge)]
    bm.to_mesh(shirt.data)
    bm.free()
    shirt.data.update()
    log("sleeve rolls built")


def cut_knee_tears(pants, seed, knee_z):
    rng = random.Random(seed)
    centers = [Vector((0.165, -0.070, knee_z)), Vector((-0.165, -0.070, knee_z))]
    def hole(c):
        for k in centers:
            d = Vector(((c.x - k.x) / 0.085, (c.y - k.y) / 0.10, (c.z - k.z) / 0.095))
            if d.length < 1.0:
                n = noise.noise_vector(c * 40.0 + Vector((seed, 0, 0)))
                if d.length < 0.55 + 0.35 * (0.5 + 0.5 * n.x):
                    return True
        return False
    n = delete_faces(pants, hole, "knee tears")
    # Fray the rim: pull boundary verts out and add jagged teeth.
    activate(pants)
    bm = bmesh.new()
    bm.from_mesh(pants.data)
    boundary = [e for e in bm.edges if e.is_boundary]
    for e in boundary:
        for v in e.verts:
            if any((v.co - k).length < 0.14 for k in centers):
                nvec = noise.noise_vector(v.co * 70.0)
                v.co += Vector((nvec.x, nvec.y, nvec.z)) * 0.006
    for e in boundary:
        if any((e.verts[0].co - k).length < 0.13 for k in centers):
            try:
                ret = bmesh.ops.extrude_edge_only(bm, edges=[e])
                for v in [g for g in ret["geom"] if isinstance(g, bmesh.types.BMVert)]:
                    v.co += Vector((0, 0, -0.008 - abs(noise.noise_vector(v.co * 50.0).x) * 0.012))
            except Exception:
                pass
    bm.to_mesh(pants.data)
    bm.free()
    pants.data.update()
    log("knee tears cut", n)


def add_cargo_pockets(pants, knee_z):
    parts = []
    base = knee_z + 0.17
    for side in (-1, 1):
        parts.append(surface_panel(
            pants, "Pants.cargo",
            lambda c, side=side: side * c.x > 0.06 and abs(c.y) < 0.085 and
            base < c.z < base + 0.135,
            offset=0.007, thickness=0.005))
    join_objects(pants, parts)
    log("cargo pockets joined")


def add_waistband_and_belt(pants, body, rig, seed, waist_z):
    # Cut a belt directly from the top band of the trousers.  Raycasting a ring was
    # unreliable where the waist curves, producing a single face stretched across the body.
    belt_obj = extract_surface(pants, "Belt",
                               lambda c: c.z > waist_z - 0.050 and abs(c.x) < 0.26)
    offset_surface(belt_obj, 0.010, wrinkle=0.002, seed=seed + 9)
    sol = belt_obj.modifiers.new("Thickness", 'SOLIDIFY')
    sol.thickness = 0.010
    apply_modifier(belt_obj, sol.name)
    # Buckle: find the actual front of the belt.
    ok, loc, nor, _ = belt_obj.ray_cast(Vector((0, -2.0, waist_z - 0.012)), Vector((0, 1, 0)))
    by = loc.y - 0.012 if ok else -0.11
    buckle = make_buckle(Vector((0, by, waist_z - 0.012)))
    log("belt built")
    return belt_obj, None, buckle


def extract_ring_from_boundary(source, min_z, name):
    activate(source)
    bm = bmesh.new()
    bm.from_mesh(source.data)
    edges = [e for e in bm.edges if e.is_boundary and e.verts[0].co.z > min_z and e.verts[1].co.z > min_z]
    if not edges:
        bm.free()
        return None
    ret = bmesh.ops.extrude_edge_only(bm, edges=edges)
    for v in [g for g in ret["geom"] if isinstance(g, bmesh.types.BMVert)]:
        v.co.z += 0.030
    me = bpy.data.meshes.new(name)
    # Keep only the new band.
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    return ob


def make_buckle(center):
    bm = bmesh.new()
    w, h, bar = 0.045, 0.030, 0.006
    outer = [(-w, -h), (w, -h), (w, h), (-w, h)]
    inner = [(-w + bar, -h + bar), (w - bar, -h + bar), (w - bar, h - bar), (-w + bar, h - bar)]
    vo = [bm.verts.new((x, 0, z)) for x, z in outer]
    vi = [bm.verts.new((x, 0, z)) for x, z in inner]
    for i in range(4):
        j = (i + 1) % 4
        bm.faces.new((vo[i], vo[j], vi[j], vi[i]))
    bm.normal_update()
    me = bpy.data.meshes.new("Buckle")
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new("Buckle", me)
    bpy.context.scene.collection.objects.link(ob)
    ob.location = center
    sol = ob.modifiers.new("Thickness", 'SOLIDIFY')
    sol.thickness = 0.005
    apply_modifier(ob, sol.name)
    bevel = ob.modifiers.new("Bevel", 'BEVEL')
    bevel.width = 0.0015
    bevel.segments = 2
    apply_modifier(ob, bevel.name)
    return ob


def add_boots(boots, rig, seed):
    """Finish MPFB's fitted shoe: bake the fit, loft a shaft, bind each foot rigidly.

    The asset has to be created while the base buffer still has its helper vertices (the
    .mhclo indexing depends on it), so the object is built in make_human and dressed here.
    """
    boots.data.materials.clear()
    # Deform now, then drop the asset modifiers: keeping MPFB's shoe weights after the macro
    # bake throws the pair across the scene because the rest spaces no longer match.
    activate(boots)
    for mod in list(boots.modifiers):
        if mod.type != 'ARMATURE':
            apply_modifier(boots, mod.name)
    detach_asset(boots)
    extend_boot_shaft(boots)
    for vg in list(boots.vertex_groups):
        boots.vertex_groups.remove(vg)
    gL = boots.vertex_groups.new(name="foot.L")
    gR = boots.vertex_groups.new(name="foot.R")
    for v in boots.data.vertices:
        (gL if v.co.x > 0 else gR).add([v.index], 1.0, 'REPLACE')
    boots.parent = rig
    arm = boots.modifiers.new("Armature", 'ARMATURE')
    arm.object = rig
    log("boots added", len(boots.data.vertices), "verts")
    return boots


def extend_boot_shaft(boots, steps=5, dz=0.043, taper=0.985):
    """Loft a leather shaft up from each ankle opening.

    Extruding every boundary edge of the shoe is what exploded the earlier version: the
    sole and lace boundaries got pushed upward too.  Here only the highest closed loop of
    each foot is walked in order and bridged ring by ring.
    """
    bm = bmesh.new()
    bm.from_mesh(boots.data)
    bm.verts.ensure_lookup_table()
    top = max(v.co.z for v in bm.verts)
    boundary = [e for e in bm.edges if e.is_boundary]
    # Group boundary edges into connected loops.
    loops = []
    pool = set(boundary)
    while pool:
        e = pool.pop()
        loop = [e]
        stack = [e]
        while stack:
            cur = stack.pop()
            for v in cur.verts:
                for nb in v.link_edges:
                    if nb in pool and nb.is_boundary:
                        pool.remove(nb)
                        loop.append(nb)
                        stack.append(nb)
        loops.append(loop)
    made = 0
    for loop in loops:
        zs = [v.co.z for e in loop for v in e.verts]
        if not zs or (sum(zs) / len(zs)) < top - 0.05:
            continue
        xs = [v.co.x for e in loop for v in e.verts]
        if max(xs) - min(xs) < 0.025:
            continue
        # Walk the loop into an ordered vertex ring.
        adj = {}
        for e in loop:
            a, b = e.verts
            adj.setdefault(a, []).append(b)
            adj.setdefault(b, []).append(a)
        start = next(iter(adj))
        ring = [start]
        prev = None
        cur = start
        while True:
            nxt = next((n for n in adj[cur] if n is not prev), None)
            if nxt is None or nxt is start or len(ring) > len(loop) + 2:
                break
            ring.append(nxt)
            prev, cur = cur, nxt
        if len(ring) < 6:
            continue
        prev_ring = ring
        for step in range(steps):
            new_ring = []
            for v in prev_ring:
                nv = bm.verts.new((v.co.x * taper, v.co.y * taper, v.co.z + dz))
                new_ring.append(nv)
            for i in range(len(prev_ring)):
                j = (i + 1) % len(prev_ring)
                try:
                    bm.faces.new((prev_ring[i], prev_ring[j], new_ring[j], new_ring[i]))
                except ValueError:
                    pass
            prev_ring = new_ring
        made += 1
    bm.to_mesh(boots.data)
    bm.free()
    boots.data.update()
    log("boot shafts lofted", made)


# ----------------------------------------------------------------------------------
# Pose
# ----------------------------------------------------------------------------------

def apply_pose(rig, tag="stand", body=None, phase=None):
    """Bend the skeleton into the reference silhouette.

    Rotations are applied in armature space around each bone's own head, not in bone-local
    axes: the rig's bone rolls vary per chain, and local Euler guesses sent the arms through
    the torso.  Parents are rotated before children so the limbs follow naturally.
    """
    activate(rig)

    def turn(name, axis, degrees):
        pb = rig.pose.bones.get(name)
        if pb is None or abs(degrees) < 1e-6:
            return
        bpy.context.view_layer.update()
        m = pb.matrix.copy()
        pivot = m.translation.copy()
        rot = Matrix.Rotation(math.radians(degrees), 4, axis)
        pb.matrix = Matrix.Translation(pivot) @ rot @ Matrix.Translation(-pivot) @ m
        bpy.context.view_layer.update()

    def aim(name, target_world, ref="head"):
        """Point a bone at a world-space target by rotating it about its own head."""
        pb = rig.pose.bones.get(name)
        if pb is None:
            return
        bpy.context.view_layer.update()
        inv = rig.matrix_world.inverted()
        target = inv @ Vector(target_world)
        head = pb.head.copy()
        d = target - head
        if d.length < 1e-6:
            return
        cur = (pb.tail - pb.head).normalized()
        q = cur.rotation_difference(d.normalized())
        R = q.to_matrix().to_4x4()
        pb.matrix = Matrix.Translation(head) @ R @ Matrix.Translation(-head) @ pb.matrix
        bpy.context.view_layer.update()

    if tag == "crawl":
        # Prone, dragging itself on its forearms.  The pose is built while the figure is
        # still standing: in that frame "towards the head along the floor" is +Z and
        # "into the floor" is -Y, so every limb is a fixed direction rather than a world
        # target.  Aiming at world points after tipping the rig is what folded the legs up
        # into the air - the targets were then read in a rotated frame.
        rig.rotation_mode = 'XYZ'
        rig.rotation_euler = (0, 0, 0)
        bpy.context.view_layer.update()

        def aim_dir(name, direction):
            """Point a bone along an armature-space direction about its own head."""
            pb = rig.pose.bones.get(name)
            if pb is None:
                return
            d = Vector(direction)
            if d.length < 1e-6:
                return
            bpy.context.view_layer.update()
            head = pb.head.copy()
            cur = (pb.tail - pb.head).normalized()
            q = cur.rotation_difference(d.normalized())
            pb.matrix = (Matrix.Translation(head) @ q.to_matrix().to_4x4()
                         @ Matrix.Translation(-head) @ pb.matrix)
            bpy.context.view_layer.update()

        # The chest is dragged off the floor: in prone, arching backwards lifts it, which is
        # a negative X turn for the spine.
        for name, deg in (("spine01", -2), ("spine02", -3), ("spine03", -4),
                          ("spine04", -5), ("spine05", -6)):
            turn(name, 'X', deg)
        # Head cranked up off the floor, jaw hanging open.
        turn("neck01", 'X', -19)
        turn("neck02", 'X', -12)
        turn("neck03", 'X', -7)
        turn("head", 'X', 6)
        for side, s in (("L", 1), ("R", -1)):
            # Arms alternate between reaching ahead and dragging the chest forward; the
            # phase offset of half a cycle per side gives the hand-over-hand crawl.
            sw = math.sin(phase + (0.0 if side == "L" else math.pi)) if phase is not None else 0.0
            # Elbows gather under the chest and the forearms lie flat, palms planted ahead
            # of the head: a body being dragged, not a swan dive.
            aim_dir("upperarm01." + side, (s * 0.42, -0.70 - 0.16 * sw, 0.58 + 0.10 * sw))
            aim_dir("upperarm02." + side, (s * 0.40, -0.72 - 0.16 * sw, 0.58 + 0.10 * sw))
            aim_dir("lowerarm01." + side, (s * 0.10, -0.24 - 0.24 * sw, 0.96))
            aim_dir("hand." + side, (s * 0.06, -0.34 - 0.16 * sw, 0.94))
            # Legs trail straight out behind, thighs sagging down onto the floor.
            aim_dir("upperleg01." + side, (s * 0.07, -0.05 - 0.06 * sw, -1.0))
            aim_dir("lowerleg01." + side, (s * 0.05, -0.03 + 0.05 * sw, -1.0))
            # The foot and boot keep their rest angle to the shin, so the boots trail flat
            # with the soles up and the toes brushing the floor.
            for finger in ("finger1-1", "finger2-1", "finger3-1", "finger4-1", "finger5-1"):
                turn(finger + "." + side, 'X', -26)
                turn(finger.replace("-1", "-2") + "." + side, 'X', -16)
        bpy.context.view_layer.update()
        # Tip the whole figure face-down.  The rig origin sits at the feet, so the body
        # swings below the floor; then drop it onto the ground and centre the pile.
        rig.rotation_euler = (math.radians(90), 0, 0)
        bpy.context.view_layer.update()
        if body is not None:
            deps = bpy.context.evaluated_depsgraph_get()
            ev = body.evaluated_get(deps)
            me = ev.to_mesh()
            pts = [ev.matrix_world @ v.co for v in me.vertices]
            ev.to_mesh_clear()
            if pts:
                low = min(p.z for p in pts)
                rig.location.z += 0.010 - low
                bpy.context.view_layer.update()
                xs = [p.x for p in pts]
                ys = [p.y for p in pts]
                rig.location.x -= (min(xs) + max(xs)) / 2
                rig.location.y -= (min(ys) + max(ys)) / 2
        bpy.context.view_layer.update()
    else:
        # Rounded upper back, sunken chest.
        for name, deg in (("spine05", 9), ("spine04", 5), ("spine03", 3), ("spine02", 2)):
            turn(name, 'X', deg)
        # Skull pushed forward and down.
        turn("neck01", 'X', 9)
        turn("neck02", 'X', 7)
        turn("neck03", 'X', 6)
        turn("head", 'X', -4)
        for side, s in (("L", 1), ("R", -1)):
            # Arms hang forward out of the shoulders.
            turn("shoulder01." + side, 'X', 6)
            turn("upperarm01." + side, 'X', 26)
            turn("upperarm01." + side, 'Y', 16 * s)
            turn("upperarm02." + side, 'X', 6)
            turn("lowerarm01." + side, 'X', 22)
            turn("hand." + side, 'X', 10)
            for finger in ("finger1-1", "finger2-1", "finger3-1", "finger4-1", "finger5-1"):
                turn(finger + "." + side, 'X', 16)
                turn(finger.replace("-1", "-2") + "." + side, 'X', 22)
                turn(finger.replace("-1", "-3") + "." + side, 'X', 12)
            # Weight sunk, knees unlocked.
            turn("upperleg01." + side, 'X', -5)
            turn("lowerleg01." + side, 'X', 9)
            turn("foot." + side, 'X', -4)
        if phase is not None:
            # One frame of the shamble: legs swing, knees fold on the swing leg, arms
            # counter-swing, and the shoulders roll against the hips.
            for side, off in (("L", 0.0), ("R", math.pi)):
                sw = math.sin(phase + off)
                turn("upperleg01." + side, 'X', 21.0 * sw)
                turn("lowerleg01." + side, 'X', 10.0 + 16.0 * max(0.0, sw))
                turn("foot." + side, 'X', -9.0 * sw)
                turn("upperarm01." + side, 'X', -17.0 * sw)
                turn("lowerarm01." + side, 'X', 5.0 + 7.0 * max(0.0, -sw))
            turn("spine05", 'Y', 2.5 * math.sin(phase))
            turn("spine03", 'Y', -1.5 * math.sin(phase))
            turn("head", 'Y', -3.0 * math.sin(phase))
    bpy.context.view_layer.update()
    log("pose applied:", tag, "phase" if phase is not None else "")


# ----------------------------------------------------------------------------------
# Weights
# ----------------------------------------------------------------------------------

def transfer_weights(obj, body):
    activate(obj)
    dt = obj.modifiers.new("BodyWeights", 'DATA_TRANSFER')
    dt.object = body
    dt.use_vert_data = True
    dt.data_types_verts = {'VGROUP_WEIGHTS'}
    dt.vert_mapping = 'POLYINTERP_NEAREST'
    dt.layers_vgroup_select_src = 'ALL'
    dt.layers_vgroup_select_dst = 'NAME'
    bpy.ops.object.datalayout_transfer(modifier=dt.name)
    apply_modifier(obj, dt.name)
    arm = obj.modifiers.new("Armature", 'ARMATURE')
    arm.object = bpy.data.objects["Zombie.rig"]
    obj.parent = bpy.data.objects["Zombie.rig"]
    log("weights transferred", obj.name, len(obj.vertex_groups))


def transfer_weights_objects(objects, body):
    for o in objects:
        if o.type == 'MESH':
            transfer_weights(o, body)


# ----------------------------------------------------------------------------------
# Render setup
# ----------------------------------------------------------------------------------

def studio(scene, samples=48):
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.render.film_transparent = False
    scene.view_settings.view_transform = 'Filmic'
    scene.view_settings.look = 'Medium High Contrast'
    # Floor.
    bpy.ops.mesh.primitive_plane_add(size=12, location=(0, 0, -0.002))
    floor = bpy.context.object
    floor.name = "Studio.floor"
    mat = bpy.data.materials.new("Studio.floor.mat")
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (0.035, 0.037, 0.040, 1)
    bsdf.inputs["Roughness"].default_value = 0.82
    floor.data.materials.append(mat)
    # Lights.
    def area(name, loc, energy, size, color=(1, 1, 1)):
        ld = bpy.data.lights.new(name, 'AREA')
        ld.energy = energy
        ld.shape = 'DISK'
        ld.size = size
        ld.color = color
        ob = bpy.data.objects.new(name, ld)
        scene.collection.objects.link(ob)
        ob.location = loc
        direction = Vector((0, 0, 1.15)) - ob.location
        ob.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    area("Key", (2.6, -3.0, 2.9), 700, 2.2, (1.0, 0.96, 0.90))
    area("Fill", (-2.8, -1.8, 1.3), 150, 2.8, (0.72, 0.82, 0.95))
    area("Rim", (0.4, 3.2, 2.4), 500, 1.8, (0.95, 0.90, 0.82))
    # Camera.
    cam_data = bpy.data.cameras.new("ZombieCam")
    cam = bpy.data.objects.new("ZombieCam", cam_data)
    scene.collection.objects.link(cam)
    cam_data.type = 'ORTHO'
    cam_data.ortho_scale = 2.02
    scene.camera = cam
    return cam


def point_camera(cam, location, target=Vector((0, 0, 0.92)), ortho=2.02):
    cam.location = location
    cam.data.ortho_scale = ortho
    direction = target - Vector(location)
    cam.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()


def render_turnaround(scene, cam, tag, samples=48, sprite=False, target=None, ortho=2.02):
    result = {}
    t = target if target is not None else Vector((0, 0, 0.92))
    r = ortho
    views = [("front", t + Vector((0, -5.0, 0.03))),
             ("side", t + Vector((5.0, 0, 0.03))),
             ("back", t + Vector((0, 5.0, 0.03)))]
    scene.render.resolution_x = 1024
    scene.render.resolution_y = 1536
    scene.render.image_settings.file_format = 'PNG'
    for name, loc in views:
        point_camera(cam, loc, target=t, ortho=r)
        scene.render.filepath = os.path.join(PREVIEWS, f"{tag}_{name}.png")
        bpy.ops.render.render(write_still=True)
        result[name] = scene.render.filepath
        log("rendered", name)
    if sprite:
        scene.render.film_transparent = True
        scene.render.resolution_x = 640
        scene.render.resolution_y = 960
        point_camera(cam, t + Vector((0, -5.0, 0.03)), target=t, ortho=r)
        scene.render.filepath = os.path.join(PREVIEWS, f"{tag}_sprite.png")
        bpy.ops.render.render(write_still=True)
        scene.render.film_transparent = False
    return result


def face_target(body):
    """Centre of the posed face, used to aim the close-up cameras."""
    deps = bpy.context.evaluated_depsgraph_get()
    ev = body.evaluated_get(deps)
    me = ev.to_mesh()
    mw = ev.matrix_world
    pts = [mw @ v.co for v in me.vertices]
    top = max(p.z for p in pts)
    head = [p for p in pts if p.z > top - 0.20 and p.y < 0.02]
    ev.to_mesh_clear()
    if not head:
        return Vector((0, -0.14, 1.65))
    return Vector((sum(p.x for p in head) / len(head),
                   min(p.y for p in head),
                   sum(p.z for p in head) / len(head)))


def render_head(scene, cam, tag, body, samples=48, target=None):
    """Close-up of the face; the whole character read depends on this shot."""
    t = target if target is not None else face_target(body)
    scene.render.resolution_x = 900
    scene.render.resolution_y = 1000
    aim = t + Vector((0, 0, 0.012))
    point_camera(cam, t + Vector((0.0, -3.0, 0.010)), target=aim, ortho=0.30)
    scene.render.filepath = os.path.join(PREVIEWS, f"{tag}_head.png")
    bpy.ops.render.render(write_still=True)
    point_camera(cam, t + Vector((2.0, -2.2, 0.06)), target=aim, ortho=0.32)
    scene.render.filepath = os.path.join(PREVIEWS, f"{tag}_head34.png")
    bpy.ops.render.render(write_still=True)
    log("rendered head close-up", tuple(round(v, 3) for v in t))


# ----------------------------------------------------------------------------------
# Animation
# ----------------------------------------------------------------------------------

def build_animation(rig, body, tag, name, frames=24):
    """Bake a looping cycle into an action by re-posing the rig every frame.

    Each frame is rebuilt from the rest pose with apply_pose(phase=...), so the clip is made
    of exactly the poses the stills use.  No second, hand-keyed rig to drift out of sync
    with the mesh.
    """
    scene = bpy.context.scene
    scene.render.fps = 24
    rig.animation_data_clear()
    act = bpy.data.actions.new(name)
    act.use_fake_user = True
    rig.animation_data_create()
    rig.animation_data.action = act
    for f in range(frames + 1):
        phase = 2.0 * math.pi * (f % frames) / frames
        scene.frame_set(f + 1)
        rig.rotation_mode = 'XYZ'
        rig.rotation_euler = (0, 0, 0)
        rig.location = (0, 0, 0)
        for pb in rig.pose.bones:
            pb.matrix_basis.identity()
        bpy.context.view_layer.update()
        apply_pose(rig, tag, body, phase=phase)
        for pb in rig.pose.bones:
            pb.keyframe_insert("rotation_quaternion", frame=f + 1, group=pb.name)
            pb.keyframe_insert("location", frame=f + 1, group=pb.name)
        rig.keyframe_insert("location", frame=f + 1)
        rig.keyframe_insert("rotation_euler", frame=f + 1)
    # Blender 5 actions are slotted; the keys land in layered channel bags, so counting
    # curves through act.fcurves is no longer possible (and BEZIER is already the default).
    log("animation", name, frames + 1, "keys")
    return act


def rename_glb_animation(path, name):
    """Rename the single clip in a GLB.

    Blender 5's slotted actions export under the generic name "Animation" regardless of the
    action name, so the clip is renamed in the GLB's JSON chunk afterwards.  Chunks must
    stay four-byte aligned, hence the trailing spaces.
    """
    import json
    import struct
    with open(path, "rb") as fh:
        data = fh.read()
    magic, version, _total = struct.unpack("<III", data[:12])
    offset = 12
    chunks = []
    while offset < len(data):
        length, kind = struct.unpack("<II", data[offset:offset + 8])
        chunks.append((kind, data[offset + 8:offset + 8 + length]))
        offset += 8 + length
    out = []
    for kind, payload in chunks:
        if kind == 0x4E4F534A:
            doc = json.loads(payload)
            for anim in doc.get("animations", []):
                anim["name"] = name
            payload = json.dumps(doc, separators=(",", ":")).encode("utf-8")
            payload += b" " * ((4 - len(payload) % 4) % 4)
        out.append((kind, payload))
    body = b"".join(struct.pack("<II", len(p), k) + p for k, p in out)
    with open(path, "wb") as fh:
        fh.write(struct.pack("<III", magic, version, 12 + len(body)) + body)
    log("glb clip renamed", name)


def export_glb(rig, objects, path, animated):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objects:
        o.select_set(True)
    bpy.context.view_layer.objects.active = rig
    common = dict(filepath=path, export_format='GLB', use_selection=True, export_apply=False,
                  export_skins=True, export_yup=True, export_image_format='AUTO')
    if animated:
        try:
            bpy.ops.export_scene.gltf(export_animations=True, export_animation_mode='ACTIVE_ACTIONS',
                                      export_frame_range=False, **common)
            return
        except TypeError as exc:
            log("animation export args unsupported, retrying basic:", exc)
    bpy.ops.export_scene.gltf(export_animations=False, **common)


# ----------------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------------

def main():
    args = parse_args()
    t0 = time.time()
    scene = reset_scene()
    body, rig, eyes, teeth, boot_asset = make_human(args.seed, args.variant)
    sculpt_body(body, rig, args.seed)

    # Shirt and pants are cut from the body surface, then simulated.
    pelvis_z = _bone_head(rig, "pelvis.L").z
    head_z = _bone_head(rig, "head").z
    shoulder_z = _bone_head(rig, "shoulder01.L").z
    knee_z = _bone_head(rig, "lowerleg01.L").z
    # Collar line sits at the base of the neck.  Using the jaw as the top made the shirt
    # swallow the chin and lower face.
    neck_z = _bone_head(rig, "jaw").z - 0.105
    hem_z = pelvis_z + 0.095
    waist_z = pelvis_z + 0.130
    log("landmarks pelvis", round(pelvis_z,3), "head", round(head_z,3), "shoulder", round(shoulder_z,3), "knee", round(knee_z,3))
    shirt = extract_surface(body, "Zombie.shirt", lambda c: in_shirt(c, rig, hem_z, neck_z))
    cut_shirt_openings(shirt, neck_z=neck_z, hem_z=hem_z)
    offset_surface(shirt, 0.022, wrinkle=0.006, seed=args.seed)
    if not args.no_cloth:
        cloth_sim(shirt, body, pin_above=shoulder_z - 0.035, frames=24, pressure=0.25, seed=args.seed)
    if not args.simple:
        add_shirt_details(shirt, args.seed, hem_z, neck_z, shoulder_z, rig)
    straighten_boundary(shirt, hem_z - 0.035, hem_z + 0.035, hem_z)

    # The A-pose hands hang at hip height, so a height-only predicate would copy them into
    # the trousers (and then into the belt).  The leg region is narrow in X; the hands are not.
    pants = extract_surface(body, "Zombie.pants",
                            lambda c: 0.21 < c.z < waist_z + 0.015 and abs(c.x) < 0.33)
    offset_surface(pants, 0.020, wrinkle=0.006, seed=args.seed + 1)
    if not args.no_cloth:
        cloth_sim(pants, body, pin_above=waist_z - 0.02, frames=24, pressure=0.15, seed=args.seed + 1)
    cut_knee_tears(pants, args.seed, knee_z)
    if not args.simple:
        add_cargo_pockets(pants, knee_z)
    straighten_boundary(pants, 0.205, 0.250, 0.216)

    boots = add_boots(boot_asset, rig, args.seed)
    belt, waistband, buckle = add_waistband_and_belt(pants, body, rig, args.seed, waist_z)

    # Buttons are separate metal objects, parented to the nearest spine bone.
    buttons = []
    for i in range(6):
        z = neck_z - 0.075 - i * ((neck_z - hem_z - 0.10) / 5.0)
        ok, loc, nor, _ = shirt.ray_cast(Vector((0, -2.0, z)), Vector((0, 1, 0)))
        if not ok:
            continue
        b = add_cylinder("Button", loc + nor * 0.006, 0.0065, 0.0035, axis='Y', segments=16)
        bone = nearest_bone(rig, b.location)
        bind_to_bone(b, rig, bone)
        buttons.append(b)
    for i, z in enumerate((neck_z - 0.15,)):
        for side in (-1, 1):
            ok, loc, nor, _ = shirt.ray_cast(Vector((side * 0.095, -2.0, z)), Vector((0, 1, 0)))
            if ok:
                b = add_cylinder("PocketButton", loc + nor * 0.012, 0.005, 0.003, axis='Y', segments=12)
                bind_to_bone(b, rig, nearest_bone(rig, b.location))
                buttons.append(b)

    # Cloth weights transfer from the body.
    transfer_weights_objects([shirt, pants], body)
    if belt:
        bind_to_bone(belt, rig, "spine05")
    if buckle:
        bind_to_bone(buckle, rig, nearest_bone(rig, buckle.location))

    # Materials are added in the full stage; clay stage gets a simple grey.
    if args.stage == "geom":
        clay_materials(body, shirt, pants, boots, belt, buckle, eyes, teeth)
    else:
        build_materials(body, shirt, pants, boots, belt, buckle, eyes, teeth, args)

    # Pose only after the bake.  Baking in the posed state bakes pose occlusion into the
    # AO map: on the crawl it painted hard black patches under the arms, thighs and belt,
    # which then stick to the skin no matter how the rig is moved later.
    apply_pose(rig, "crawl" if args.pose == "crawl" else "stand", body)

    # Ground the character and report height.
    bpy.context.view_layer.update()
    raw_h = world_bbox(body)[1].z - world_bbox(body)[0].z
    log("body height", round(raw_h, 3), "m")
    if args.pose == "crawl":
        # A prone body has no standing height to normalise against; it was already built at
        # the same skeleton scale as the standing variant.
        log("crawl pose: height normalisation skipped")
    elif raw_h > 0.1:
        s = 1.80 / raw_h
        rig.scale = (s, s, s)
        bpy.context.view_layer.update()
        log("character scaled to 1.80 m, factor", round(s, 4))
    meshes = [body, shirt, pants, boots, eyes, teeth]
    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    for o in meshes:
        a, b = world_bbox(o)
        lo.x, lo.y, lo.z = min(lo.x, a.x), min(lo.y, a.y), min(lo.z, a.z)
        hi.x, hi.y, hi.z = max(hi.x, b.x), max(hi.y, b.y), max(hi.z, b.z)
    # Ground the whole assembly, boots included: the crawl pose tips the feet below the
    # floor, and body-only grounding leaves the boots buried.
    if abs(lo.z - 0.003) > 0.002:
        rig.location.z -= (lo.z - 0.003)
        bpy.context.view_layer.update()
        lo = Vector((1e9, 1e9, 1e9))
        hi = Vector((-1e9, -1e9, -1e9))
        for o in meshes:
            a, b = world_bbox(o)
            lo = Vector((min(lo.x, a.x), min(lo.y, a.y), min(lo.z, a.z)))
            hi = Vector((max(hi.x, b.x), max(hi.y, b.y), max(hi.z, b.z)))
    log("bounds", tuple(round(v, 3) for v in lo), tuple(round(v, 3) for v in hi), "height", round(hi.z - lo.z, 3))

    # Camera/render.
    if args.no_render:
        log("render skipped")
    else:
        centre = Vector(((lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2))
        cam = studio(scene, args.samples)
        head_target = None
        if args.pose == "crawl":
            # A prone body is 1.8 m long along Y; the standing ortho scale would crop it out
            # of the side view, and the standing face-centre finder keys off height, which
            # is now the body's thickness rather than its length.
            span = (hi.y - lo.y) * 1.62
            hb = rig.pose.bones.get("head")
            if hb is not None:
                head_target = rig.matrix_world @ hb.tail + Vector((0.0, -0.045, -0.010))
        else:
            span = max(hi.x - lo.x, hi.z - lo.z) * 1.35
        render_turnaround(scene, cam, args.variant + "_" + args.pose, args.samples, sprite=True,
                          target=centre, ortho=max(2.02, span))
        render_head(scene, cam, args.variant + "_" + args.pose, body, args.samples,
                    target=head_target)

    # Export.
    export_objects = [body, rig, shirt, pants, boots, eyes, teeth, belt, buckle] + buttons
    export_objects = [o for o in export_objects if o is not None]
    animated = args.anim
    if animated:
        build_animation(rig, body, "crawl" if args.pose == "crawl" else "stand",
                        "Crawl" if args.pose == "crawl" else "Walk")
        bpy.context.scene.frame_set(1)
    glb = os.path.join(OUT, "zombie_" + args.variant + "_" + args.pose + ".glb")
    export_glb(rig, export_objects, glb, animated)
    if animated:
        rename_glb_animation(glb, "Crawl" if args.pose == "crawl" else "Walk")
    tris = triangle_count(export_objects)
    log("TRIS", tris)
    log("GLB", glb, round(os.path.getsize(glb) / 1024 / 1024, 2), "MB")
    log("TEXTURES", sorted(os.path.basename(p) for p in os.listdir(TEXTURES)))
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "zombie_" + args.variant + "_" + args.pose + ".blend"))
    log("DONE in", round(time.time() - t0, 1), "s")


def in_shirt(c, rig, hem_z, neck_z):
    if hem_z < c.z < neck_z + 0.04 and abs(c.x) < 0.29:
        return True
    # Sleeves: distance to the upper arm and lower arm until mid-forearm.
    for side in (1, -1):
        sh = _bone_head(rig, "shoulder01.L" if side > 0 else "shoulder01.R")
        el = _bone_head(rig, "lowerarm01.L" if side > 0 else "lowerarm01.R")
        mid = _bone_head(rig, "lowerarm02.L" if side > 0 else "lowerarm02.R")
        if dist_to_segment(c, sh, el) < 0.105:
            return True
        t = closest_t(c, el, mid)
        if 0.0 <= t <= 0.60 and dist_to_segment(c, el, mid) < 0.090:
            return True
    return False


def dist_to_segment(p, a, b):
    ab = b - a
    t = max(0.0, min(1.0, (p - a).dot(ab) / max(ab.length_squared, 1e-9)))
    return (p - (a + ab * t)).length


def closest_t(p, a, b):
    ab = b - a
    return max(0.0, min(1.0, (p - a).dot(ab) / max(ab.length_squared, 1e-9)))


# ----------------------------------------------------------------------------------
# Clay / materials
# ----------------------------------------------------------------------------------

def clay_materials(body, shirt, pants, boots, belt, buckle, eyes, teeth):
    def mat(name, color, rough=0.7, metal=0.0):
        m = bpy.data.materials.new(name)
        m.use_nodes = True
        b = m.node_tree.nodes["Principled BSDF"]
        b.inputs["Base Color"].default_value = (*color, 1)
        b.inputs["Roughness"].default_value = rough
        b.inputs["Metallic"].default_value = metal
        return m
    skin = mat("Clay.skin", (0.42, 0.39, 0.36), 0.62)
    fabric = mat("Clay.fabric", (0.21, 0.23, 0.13), 0.88)
    leather = mat("Clay.leather", (0.10, 0.055, 0.03), 0.6)
    metal = mat("Clay.metal", (0.22, 0.22, 0.22), 0.35, 1.0)
    eye = mat("Clay.eye", (0.55, 0.55, 0.52), 0.3)
    tooth = mat("Clay.tooth", (0.55, 0.47, 0.32), 0.45)
    body.data.materials.append(skin)
    eyes.data.materials.append(eye)
    teeth.data.materials.append(tooth)
    shirt.data.materials.append(fabric)
    pants.data.materials.append(fabric)
    boots.data.materials.append(leather)
    belt.data.materials.append(leather)
    if buckle:
        buckle.data.materials.append(metal)


def build_materials(body, shirt, pants, boots, belt, buckle, eyes, teeth, args):
    """Procedural shaders, then a full CPU bake to image maps for the glTF."""
    import materials as M
    skin = M.skin_material(args.seed)
    fabric = M.fabric_material()
    leather = M.leather_material()
    metal = M.metal_material()
    eye = M.eye_material()
    tooth = M.tooth_material()
    for obj, mat in ((body, skin), (shirt, fabric), (pants, fabric), (belt, leather),
                     (boots, leather), (eyes, eye), (teeth, tooth)):
        if obj is None:
            continue
        obj.data.materials.clear()
        obj.data.materials.append(mat)
    if buckle:
        buckle.data.materials.clear()
        buckle.data.materials.append(metal)
    M.paint_garment_masks(shirt, args.seed)
    M.paint_garment_masks(pants, args.seed + 1)

    hi, lo = 2048, 1024
    if args.bake_res:
        hi = lo = args.bake_res
    jobs = [(body, hi), (shirt, hi), (pants, hi), (boots, lo), (belt, lo),
            (eyes, lo), (teeth, lo), (buckle, lo)]
    M.bake_character([(o, r) for o, r in jobs if o is not None], TEXTURES,
                     high_res=hi, low_res=lo, samples=args.bake_samples)
    log("materials baked")


if __name__ == "__main__":
    main()
