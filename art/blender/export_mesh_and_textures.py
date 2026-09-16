"""
Re-exports Aether from the .blend with her textures.

WHY THE STOCK EXPORT HAS NO TEXTURES. This is a Character Creator 3 figure imported with the CC/iC
Blender Tools addon, so every material drives Base Color through an `rl_*_shader` node group rather
than wiring an Image Texture straight into the Principled BSDF. Blender's .obj exporter only emits
`map_Kd` when it finds that direct connection, so it fell back to writing the Principled default --
`Kd 0.800000` -- for every material. The textures were packed inside the .blend the whole time.

Rather than rewiring twenty node graphs and hoping the exporter follows, this saves the images
itself and the .mtl is written by hand afterwards.
"""
import os
import sys

import bpy
import numpy as np

OUT_DIR = sys.argv[sys.argv.index("--") + 1]
TEX_DIR = os.path.join(OUT_DIR, "aether_tex")
os.makedirs(TEX_DIR, exist_ok=True)

# The character without clothing: the Bra and Underwear_Bottoms meshes are deliberately left out,
# since custom clothing is coming later and shipping these would only mean unpicking them again.
# `blender file_Collider` is hide_render, the WGT-rig_* objects are armature widgets, and
# EyeOcclusion/TearLine are low-opacity eye helpers the renderer discards anyway.
EXPORT_OBJECTS = ["Body", "Eye", "LongCurly", "Teeth", "Tongue"]

# Output resolution per material, budgeted against how much screen each actually covers. The head
# is the focal point of the framing and gets the most; the hair needs it too, because its opacity
# map is an alpha cutout and downscaling one eats the thin strands at the edge of every card.
PLAN = {
    "Skin_Head":         (2048, "JPEG"),
    "Skin_Body":         (1024, "JPEG"),
    "Skin_Arm":          (1024, "JPEG"),
    "Skin_Leg":          (1024, "JPEG"),
    "Nails":             (512,  "JPEG"),
    "Eyelash":           (1024, "RGBA"),
    "Hair_Transparency_Transparency": (2048, "RGBA"),
    "Scalp_Transparency_Transparency": (1024, "SCALP"),
    "Upper_Teeth":       (512,  "JPEG"),
    "Lower_Teeth":       (512,  "JPEG"),
    "Tongue":            (512,  "JPEG"),
    "Cornea_L":          (1024, "JPEG"),
    "Cornea_R":          (1024, "JPEG"),
}


def maps_for(mat):
    """The DIFFUSE and ALPHA images feeding a cc3 material, found by the group socket they land on."""
    diffuse = alpha = sclera = None
    if not (mat.use_nodes and mat.node_tree):
        return None, None, None
    for n in mat.node_tree.nodes:
        if n.type != "TEX_IMAGE" or not n.image:
            continue
        for outp in n.outputs:
            for lnk in outp.links:
                socket = lnk.to_socket.name
                if socket in ("Diffuse Map", "Cornea Diffuse Map") and diffuse is None:
                    diffuse = n.image
                elif socket == "Sclera Diffuse Map" and sclera is None:
                    sclera = n.image
                elif socket == "Alpha Map":
                    alpha = n.image
    return diffuse, alpha, sclera


def pixels_of(image, size):
    copy = image.copy()
    copy.scale(size, size)
    buf = np.empty(size * size * 4, dtype=np.float32)
    copy.pixels.foreach_get(buf)
    bpy.data.images.remove(copy)
    return buf.reshape(-1, 4)


def save_rgba(rgba, size, path):
    out = bpy.data.images.new("tmp_out", size, size, alpha=True)
    out.pixels.foreach_set(rgba.reshape(-1))
    out.filepath_raw = path
    out.file_format = "PNG"
    out.save()
    bpy.data.images.remove(out)


written = {}
hair_rgb = None

# Hair first: the scalp borrows its colour, so it has to exist before the scalp is built.
order = sorted(PLAN, key=lambda n: 0 if n.startswith("Hair") else 1)

for name in order:
    size, mode = PLAN[name]
    mat = bpy.data.materials.get(name)
    if mat is None:
        print("SKIP missing material", name)
        continue
    diffuse, alpha, _ = maps_for(mat)

    if mode == "SCALP":
        # THE BALD SPOT. This material is the cap the hair cards grow out of, and the export gives
        # it an opacity mask but no diffuse of any kind -- so the first pass dropped it for having
        # no map_Kd, and what showed through the gaps between hair cards was bare scalp skin.
        #
        # There is no colour in the file to recover, so it is built: the hair's own average colour
        # under its opacity mask, darkened towards the roots, carried on the scalp's real alpha
        # mask. That puts hair-coloured shadow under the parting instead of skin.
        if alpha is None or hair_rgb is None:
            print("SKIP scalp, no alpha map or no hair colour")
            continue
        rgba = np.empty((size * size, 4), dtype=np.float32)
        rgba[:, 0] = hair_rgb[0] * 0.75
        rgba[:, 1] = hair_rgb[1] * 0.75
        rgba[:, 2] = hair_rgb[2] * 0.75
        rgba[:, 3] = pixels_of(alpha, size)[:, 0]
        filename = name + ".png"
        save_rgba(rgba, size, os.path.join(TEX_DIR, filename))
        written[name] = filename
        print("TEX", name, "->", filename, "flatColour=", tuple(round(float(v * 0.75), 4) for v in hair_rgb))
        continue

    if diffuse is None:
        print("SKIP no diffuse for", name)
        continue

    if mode == "RGBA":
        if alpha is None:
            print("SKIP no alpha for", name)
            continue
        rgba = pixels_of(diffuse, size)
        a = pixels_of(alpha, size)[:, 0]
        rgba[:, 3] = a
        if name.startswith("Hair"):
            lit = a > 0.5
            hair_rgb = rgba[lit, :3].mean(axis=0) if lit.any() else np.array([0.2, 0.15, 0.1])
            # How much of the card survives each candidate cutoff, so the Android side's threshold
            # is chosen against the real distribution rather than guessed.
            print("HAIR alpha coverage:", {
                round(t, 2): round(float((a > t).mean()), 4) for t in (0.1, 0.25, 0.35, 0.5, 0.75)
            })
        filename = name + ".png"
        save_rgba(rgba, size, os.path.join(TEX_DIR, filename))
    else:
        copy = diffuse.copy()
        copy.scale(size, size)
        filename = name + ".jpg"
        copy.filepath_raw = os.path.join(TEX_DIR, filename)
        copy.file_format = "JPEG"
        copy.save()
        bpy.data.images.remove(copy)

    written[name] = filename
    print("TEX", name, "->", filename, size, os.path.getsize(os.path.join(TEX_DIR, filename)), "bytes")

# --- geometry ------------------------------------------------------------------------------

# `bpy.ops.object.select_all` needs a 3D-viewport context that -b never provides, so selection is
# driven straight off the view layer instead.
view_layer = bpy.context.view_layer
for o in view_layer.objects:
    try:
        o.select_set(False)
    except RuntimeError:
        pass

exported = []
for name in EXPORT_OBJECTS:
    obj = bpy.data.objects.get(name)
    if obj is None:
        print("SKIP missing object", name)
        continue
    try:
        obj.hide_viewport = False
        obj.hide_set(False)
        obj.select_set(True)
        view_layer.objects.active = obj
        exported.append(name)
    except RuntimeError as e:
        print("SKIP cannot select", name, e)
print("EXPORTING", exported)

obj_path = os.path.join(OUT_DIR, "Aether.obj")
options = dict(
    filepath=obj_path,
    export_selected_objects=True,
    export_uv=True,
    export_normals=True,
    export_materials=True,
    # Dropped deliberately: with real diffuse maps the per-vertex greys are no longer the model's
    # appearance, and multiplying them over a texture would only dim it.
    export_colors=False,
    export_triangulated_mesh=False,
    path_mode="COPY",
)
known = set(bpy.ops.wm.obj_export.get_rna_type().properties.keys())
bpy.ops.wm.obj_export(**{k: v for k, v in options.items() if k in known})
print("OBJ written", os.path.getsize(obj_path))
print("DONE")
