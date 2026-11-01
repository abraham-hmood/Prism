"""Procedural zombie materials and CPU texture baking.

Everything is built from nodes so the look is reproducible from a seed, then baked to
image maps so the exported glTF carries the same appearance into the game.
"""

import bpy
import math
import os


def log(*args):
    print("[ZOMBIE]", *args, flush=True)


def _tree(mat):
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    out.location = (900, 0)
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.location = (600, 0)
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    return nt, bsdf


def _noise(nt, scale, detail=6.0, rough=0.55, loc=(-700, 0), coords=None):
    n = nt.nodes.new("ShaderNodeTexNoise")
    n.location = loc
    n.inputs["Scale"].default_value = scale
    n.inputs["Detail"].default_value = detail
    n.inputs["Roughness"].default_value = rough
    if coords is not None:
        nt.links.new(coords, n.inputs["Vector"])
    return n


def _coords(nt):
    tc = nt.nodes.new("ShaderNodeTexCoord")
    tc.location = (-1000, 0)
    return tc.outputs["Object"]


def _ramp(nt, stops, loc=(-450, 0)):
    r = nt.nodes.new("ShaderNodeValToRGB")
    r.location = loc
    el = r.color_ramp.elements
    el[0].position = stops[0][0]
    el[0].color = (*stops[0][1], 1)
    el[1].position = stops[-1][0]
    el[1].color = (*stops[-1][1], 1)
    for pos, col in stops[1:-1]:
        e = el.new(pos)
        e.color = (*col, 1)
    return r


def _mix(nt, blend, fac, a, b, loc=(-200, 0)):
    """Mix two colours; fac may be a socket or a float."""
    m = nt.nodes.new("ShaderNodeMix")
    m.data_type = 'RGBA'
    m.blend_type = blend
    m.location = loc
    if hasattr(fac, "is_output"):
        nt.links.new(fac, m.inputs["Factor"])
    else:
        m.inputs["Factor"].default_value = fac
    if hasattr(a, "is_output"):
        nt.links.new(a, m.inputs[6])
    else:
        m.inputs[6].default_value = (*a, 1)
    if hasattr(b, "is_output"):
        nt.links.new(b, m.inputs[7])
    else:
        m.inputs[7].default_value = (*b, 1)
    return m.outputs[2]


def _attr(nt, name, loc=(-800, -300)):
    a = nt.nodes.new("ShaderNodeAttribute")
    a.attribute_name = name
    a.location = loc
    return a


def _bump(nt, bsdf, height, strength, loc=(300, -300)):
    b = nt.nodes.new("ShaderNodeBump")
    b.location = loc
    b.inputs["Strength"].default_value = strength
    nt.links.new(height, b.inputs["Height"])
    nt.links.new(b.outputs["Normal"], bsdf.inputs["Normal"])
    return b


# ----------------------------------------------------------------------------------
# Skin
# ----------------------------------------------------------------------------------

def skin_material(seed=0):
    mat = bpy.data.materials.new("Zombie.skin")
    nt, bsdf = _tree(mat)
    co = _coords(nt)
    # Pale, bloodless base with mottled grey patches and darker necrotic areas.
    blotch = _noise(nt, 5.5, 8, 0.62, (-800, 250), co)
    base = _ramp(nt, [(0.24, (0.108, 0.112, 0.092)), (0.44, (0.200, 0.204, 0.166)),
                      (0.64, (0.300, 0.300, 0.248)), (0.84, (0.404, 0.398, 0.330))], (-560, 250))
    nt.links.new(blotch.outputs["Fac"], base.inputs["Fac"])
    # Broad bruised patches under the pale top layer.
    patch = _noise(nt, 3.1, 5, 0.7, (-800, 420), co)
    patch_ramp = _ramp(nt, [(0.40, (0.36, 0.30, 0.31)), (0.62, (0.55, 0.51, 0.49))], (-560, 420))
    nt.links.new(patch.outputs["Fac"], patch_ramp.inputs["Fac"])
    base = _mix(nt, 'MIX', 0.35, base.outputs[0], patch_ramp.outputs[0], (-360, 330))
    # Fine pores / drying cracks.
    fine = _noise(nt, 180.0, 6, 0.7, (-800, 60), co)
    pore = _ramp(nt, [(0.42, (0.26, 0.235, 0.20)), (0.60, (0.54, 0.50, 0.44))], (-560, 60))
    nt.links.new(fine.outputs["Fac"], pore.inputs["Fac"])
    skin = _mix(nt, 'MULTIPLY', 0.55, base, pore.outputs[0], (-300, 200))
    # Discoloured patches rather than a vein network: a visible voronoi lattice over the
    # whole skull reads as eggshell, not bruising.
    bruise = _noise(nt, 2.2, 4, 0.55, (-800, -120), co)
    bruise_mask = _ramp(nt, [(0.42, (0, 0, 0)), (0.62, (1, 1, 1))], (-560, -120))
    nt.links.new(bruise.outputs["Fac"], bruise_mask.inputs["Fac"])
    bruised = _mix(nt, 'MIX', 0.55, skin, (0.30, 0.26, 0.28), (-100, 120))
    skin = _mix(nt, 'MIX', bruise_mask.outputs[0], skin, bruised, (60, 120))
    # Wound mask from the mesh (R channel): raw tissue, then dried blood.
    wound = _attr(nt, "wound")
    raw = _noise(nt, 60.0, 8, 0.75, (-800, -380), co)
    flesh = _ramp(nt, [(0.35, (0.155, 0.045, 0.036)), (0.62, (0.235, 0.070, 0.050)),
                       (0.85, (0.090, 0.026, 0.022))], (-560, -380))
    nt.links.new(raw.outputs["Fac"], flesh.inputs["Fac"])
    skin = _mix(nt, 'MIX', wound.outputs["Fac"], skin, flesh.outputs[0], (240, 60))
    # Blood: dried around the mouth, chest tears and the ruined forearms.
    blood = _attr(nt, "blood", (-800, -700))
    skin = _mix(nt, 'MIX', blood.outputs["Fac"], skin, (0.115, 0.020, 0.016), (330, 10))
    # Grime darkens creases and the filth line at the boots.
    grime = _attr(nt, "grime", (-800, -560))
    grime_f = nt.nodes.new("ShaderNodeMath")
    grime_f.operation = 'MULTIPLY'
    grime_f.inputs[1].default_value = 0.6
    grime_f.location = (200, -200)
    nt.links.new(grime.outputs["Fac"], grime_f.inputs[0])
    skin = _mix(nt, 'MULTIPLY', grime_f.outputs[0], skin,
                (0.58, 0.56, 0.52), (380, 40))
    nt.links.new(skin, bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.62
    bsdf.inputs["Subsurface Weight"].default_value = 0.12
    bsdf.inputs["Subsurface Radius"].default_value = (0.012, 0.004, 0.003)
    # Pore-level relief: a second, much finer bump carried by the normal bake.
    pores = _noise(nt, 820.0, 4, 0.7, (-800, -880), co)
    b1 = nt.nodes.new("ShaderNodeBump")
    b1.location = (300, -420)
    b1.inputs["Strength"].default_value = 0.34
    nt.links.new(pores.outputs["Fac"], b1.inputs["Height"])
    b2 = nt.nodes.new("ShaderNodeBump")
    b2.location = (470, -420)
    b2.inputs["Strength"].default_value = 0.22
    nt.links.new(fine.outputs["Fac"], b2.inputs["Height"])
    nt.links.new(b1.outputs["Normal"], b2.inputs["Normal"])
    nt.links.new(b2.outputs["Normal"], bsdf.inputs["Normal"])
    bsdf.inputs["Subsurface Weight"].default_value = 0.08
    return mat


def eye_material():
    mat = bpy.data.materials.new("Zombie.eye")
    nt, bsdf = _tree(mat)
    co = _coords(nt)
    n = _noise(nt, 90.0, 6, 0.6, (-600, 0), co)
    cloud = _ramp(nt, [(0.35, (0.215, 0.205, 0.180)), (0.65, (0.330, 0.318, 0.286))], (-380, 0))
    nt.links.new(n.outputs["Fac"], cloud.inputs["Fac"])
    nt.links.new(cloud.outputs[0], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.22
    bsdf.inputs["Subsurface Weight"].default_value = 0.05
    return mat


def tooth_material():
    mat = bpy.data.materials.new("Zombie.tooth")
    nt, bsdf = _tree(mat)
    co = _coords(nt)
    n = _noise(nt, 120.0, 5, 0.55, (-600, 0), co)
    ramp = _ramp(nt, [(0.35, (0.52, 0.44, 0.30)), (0.75, (0.68, 0.60, 0.44))], (-380, 0))
    nt.links.new(n.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs[0], bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.36
    return mat


# ----------------------------------------------------------------------------------
# Cloth, leather, metal
# ----------------------------------------------------------------------------------

def fabric_material():
    mat = bpy.data.materials.new("Zombie.fabric")
    nt, bsdf = _tree(mat)
    co = _coords(nt)
    weave = nt.nodes.new("ShaderNodeTexWave")
    weave.location = (-800, 200)
    weave.wave_type = 'BANDS'
    weave.bands_direction = 'DIAGONAL'
    weave.inputs["Scale"].default_value = 420.0
    weave.inputs["Distortion"].default_value = 2.0
    nt.links.new(co, weave.inputs["Vector"])
    base = _ramp(nt, [(0.28, (0.115, 0.125, 0.065)), (0.58, (0.185, 0.195, 0.105)),
                      (0.82, (0.135, 0.140, 0.080))], (-520, 200))
    nt.links.new(weave.outputs["Fac"], base.inputs["Fac"])
    # Sun-bleached wear and dirt.
    wear = _noise(nt, 9.0, 8, 0.65, (-800, -40), co)
    faded = _ramp(nt, [(0.40, (0.16, 0.17, 0.10)), (0.70, (0.26, 0.26, 0.17))], (-520, -40))
    nt.links.new(wear.outputs["Fac"], faded.inputs["Fac"])
    cloth = _mix(nt, 'MIX', 0.45, base.outputs[0], faded.outputs[0], (-240, 120))
    dirt = _attr(nt, "grime", (-800, -300))
    dirt_f = nt.nodes.new("ShaderNodeMath")
    dirt_f.operation = 'MULTIPLY'
    dirt_f.inputs[1].default_value = 0.7
    dirt_f.location = (-100, -300)
    nt.links.new(dirt.outputs["Fac"], dirt_f.inputs[0])
    cloth = _mix(nt, 'MULTIPLY', dirt_f.outputs[0], cloth, (0.55, 0.53, 0.46), (0, 80))
    blood = _attr(nt, "blood", (-800, -460))
    cloth = _mix(nt, 'MIX', blood.outputs["Fac"], cloth, (0.16, 0.028, 0.020), (200, 60))
    nt.links.new(cloth, bsdf.inputs["Base Color"])
    bsdf.inputs["Roughness"].default_value = 0.92
    bsdf.inputs["Sheen Weight"].default_value = 0.15
    _bump(nt, bsdf, weave.outputs["Fac"], 0.18)
    return mat


def leather_material():
    mat = bpy.data.materials.new("Zombie.leather")
    nt, bsdf = _tree(mat)
    co = _coords(nt)
    n = _noise(nt, 42.0, 8, 0.7, (-700, 0), co)
    ramp = _ramp(nt, [(0.28, (0.028, 0.016, 0.010)), (0.58, (0.070, 0.038, 0.021)),
                      (0.85, (0.045, 0.026, 0.015))], (-480, 0))
    nt.links.new(n.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs[0], bsdf.inputs["Base Color"])
    r = _noise(nt, 120.0, 5, 0.6, (-700, -260), co)
    rr = _ramp(nt, [(0.35, (0.42, 0.42, 0.42)), (0.7, (0.68, 0.68, 0.68))], (-480, -260))
    nt.links.new(r.outputs["Fac"], rr.inputs["Fac"])
    nt.links.new(rr.outputs[0], bsdf.inputs["Roughness"])
    _bump(nt, bsdf, n.outputs["Fac"], 0.3)
    return mat


def metal_material():
    mat = bpy.data.materials.new("Zombie.metal")
    nt, bsdf = _tree(mat)
    co = _coords(nt)
    n = _noise(nt, 30.0, 6, 0.6, (-700, 0), co)
    ramp = _ramp(nt, [(0.3, (0.16, 0.16, 0.155)), (0.7, (0.34, 0.34, 0.32))], (-480, 0))
    nt.links.new(n.outputs["Fac"], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs[0], bsdf.inputs["Base Color"])
    bsdf.inputs["Metallic"].default_value = 0.92
    r = _noise(nt, 90.0, 5, 0.7, (-700, -260), co)
    rr = _ramp(nt, [(0.3, (0.28, 0.28, 0.28)), (0.75, (0.55, 0.55, 0.55))], (-480, -260))
    nt.links.new(r.outputs["Fac"], rr.inputs["Fac"])
    nt.links.new(rr.outputs[0], bsdf.inputs["Roughness"])
    _bump(nt, bsdf, n.outputs["Fac"], 0.15)
    return mat


# ----------------------------------------------------------------------------------
# Vertex masks written by the builder
# ----------------------------------------------------------------------------------

def ensure_attribute(obj, name):
    me = obj.data
    attr = me.color_attributes.get(name)
    if attr is None:
        attr = me.color_attributes.new(name=name, type='FLOAT_COLOR', domain='POINT')
        for i in range(len(me.vertices)):
            attr.data[i].color = (0.0, 0.0, 0.0, 1.0)
    return attr


def paint_garment_masks(obj, seed=0):
    """Grime in creases and hems, blood pooling around the knees and chest."""
    from mathutils import noise
    me = obj.data
    grime = ensure_attribute(obj, "grime")
    blood = ensure_attribute(obj, "blood")
    zs = [v.co.z for v in me.vertices]
    lo, hi = min(zs), max(zs)
    for i, v in enumerate(me.vertices):
        p = v.co
        n = noise.noise_vector(p * 7.0)
        # Dirt rises from the bottom of the garment.
        h = (p.z - lo) / max(hi - lo, 1e-6)
        dirt = max(0.0, 1.0 - h * 2.4) * 0.55 + abs(n.x) * 0.25
        dirt = min(1.0, dirt)
        grime.data[i].color = (dirt, dirt, dirt, 1.0)
        b = 0.0
        if p.z < 0.72:  # around the knee tears
            b = max(b, 0.85 * math.exp(-((p.z - 0.55) ** 2) / (2 * 0.10 ** 2)))
        if p.z > 1.15:  # chest wounds
            b = max(b, 0.55 * math.exp(-((p.z - 1.38) ** 2) / (2 * 0.12 ** 2)))
        b *= 0.45 + 0.55 * abs(noise.noise_vector(p * 26.0).x)
        b = min(1.0, b)
        blood.data[i].color = (b, b, b, 1.0)


# ----------------------------------------------------------------------------------
# Baking
# ----------------------------------------------------------------------------------

def _ensure_uv(obj):
    """Weld, then unwrap.  MPFB's default layout has a hard seam down the centre of the
    face, which baking procedural detail into leaves a pale stripe.

    The garments are cut from the body surface face by face.  Those faces share no
    vertices, so Smart UV Project sees thousands of one-quad islands, packs them as a grid
    of dots and leaves black gutters between them; sampling that atlas is what sprayed
    black blotches across the shirt and trousers.  Welding first lets whole panels become
    single islands and the atlas fill up.
    """
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.mesh.remove_doubles(threshold=1e-4)
    extent = max((max(v.co[i] for v in obj.data.vertices) - min(v.co[i] for v in obj.data.vertices))
                 for i in range(3))
    if obj.name in ("Zombie.body", "Zombie.eyes", "Zombie.teeth"):
        # Organic mesh: island-based unwrap keeps the face readable.
        bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.004,
                                 correct_aspect=True)
    else:
        # Garments are cut face-by-face from the body, so Smart UV Project turns them into
        # thousands of one-quad islands with black gutters between them.  Sampling that
        # atlas sprayed black and skin-coloured blotches over the clothes.  A cube
        # projection gives dense, continuous coverage; on noise-driven fabric the seams
        # are invisible.
        bpy.ops.uv.cube_project(cube_size=max(extent, 0.2), correct_aspect=True,
                                scale_to_bounds=True)
    bpy.ops.object.mode_set(mode='OBJECT')


def _bake_set(obj, mat, out_dir, res, samples=12):
    """Bake albedo, normal, roughness and AO for one object; return a baked material."""
    _ensure_uv(obj)
    nt = mat.node_tree
    img_node = nt.nodes.new("ShaderNodeTexImage")
    img_node.location = (0, -700)
    for n in nt.nodes:
        n.select = False
    img_node.select = True
    nt.nodes.active = img_node

    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    for o in bpy.context.selected_objects:
        o.select_set(True)

    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.samples = samples
    # A wide margin is the difference between clean seams and black speckles: the UV
    # islands have large empty gutters, so a few pixels of bleed is not enough.
    scene.render.bake.margin = 16
    scene.render.bake.margin_type = 'ADJACENT_FACES'
    scene.render.bake.use_clear = True

    stem = obj.name.replace("Zombie.", "").replace(".", "_")
    made = {}
    jobs = (("albedo", 'DIFFUSE', {'COLOR': True}, False),
            ("normal", 'NORMAL', None, False),
            ("roughness", 'ROUGHNESS', None, False),
            ("ao", 'AO', None, False))
    for key, bake_type, pass_filter, non_color in jobs:
        if key in ("normal", "roughness", "ao"):
            pass
        img = bpy.data.images.new("%s_%s" % (stem, key), width=res, height=res, alpha=False)
        if key in ("normal", "roughness", "ao"):
            img.colorspace_settings.name = 'Non-Color'
        img_node.image = img
        if bake_type == 'DIFFUSE':
            bpy.ops.object.bake(type='DIFFUSE', pass_filter={'COLOR'}, margin=16)
        elif bake_type == 'NORMAL':
            bpy.ops.object.bake(type='NORMAL', normal_space='TANGENT', margin=16)
        else:
            bpy.ops.object.bake(type=bake_type, margin=16)
        path = os.path.join(out_dir, "%s_%s.png" % (stem, key))
        img.filepath_raw = path
        img.file_format = 'PNG'
        img.save()
        made[key] = (img, path)
        log("baked", stem, key, res)

    nt.nodes.remove(img_node)

    baked = bpy.data.materials.new("Baked." + stem)
    bt, bbsdf = _tree(baked)
    def tex(key, non_color=False, loc=(-500, 0)):
        node = bt.nodes.new("ShaderNodeTexImage")
        node.location = loc
        node.image = made[key][0]
        if non_color:
            node.image.colorspace_settings.name = 'Non-Color'
        return node
    alb = tex("albedo", False, (-800, 200))
    ao = tex("ao", True, (-800, -100))
    rgh = tex("roughness", True, (-800, -380))
    nrm = tex("normal", True, (-800, -640))
    tinted = _mix(bt, 'MULTIPLY', 0.85, alb.outputs["Color"], ao.outputs["Color"], (-420, 120))
    bt.links.new(tinted, bbsdf.inputs["Base Color"])
    bt.links.new(rgh.outputs["Color"], bbsdf.inputs["Roughness"])
    nm = bt.nodes.new("ShaderNodeNormalMap")
    nm.location = (-360, -560)
    bt.links.new(nrm.outputs["Color"], nm.inputs["Color"])
    bt.links.new(nm.outputs["Normal"], bbsdf.inputs["Normal"])
    return baked, made


def bake_character(objects, out_dir, high_res=2048, low_res=1024, samples=12):
    """Bake every mesh, then swap in baked materials for export."""
    os.makedirs(out_dir, exist_ok=True)
    baked = {}
    for obj, res in objects:
        if not obj or obj.type != 'MESH' or not obj.data.materials:
            continue
        mat = obj.data.materials[0]
        b, made = _bake_set(obj, mat, out_dir, res, samples)
        obj.data.materials.clear()
        obj.data.materials.append(b)
        baked[obj.name] = b
    return baked
