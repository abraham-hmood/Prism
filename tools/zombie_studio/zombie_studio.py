"""
Prism's zombie asset foundry.

Builds a high-poly, clothed zombie in Blender from parametric anatomy, kit-bashed
uniform parts and procedural PBR materials, then renders turntable frames and
sprite sheets for the launcher.

Run with Blender 5.2:

  blender -b --factory-startup --python tools/zombie_studio/zombie_studio.py -- \
      --variant male --pose stand --out tools/zombie_studio/out

The script is deliberately self-contained: no external meshes, no downloads, and
every image it writes is generated here. The only input is a seed.
"""

from __future__ import annotations

import argparse
import math
import os
import random
import struct
import sys
import zlib

import bpy
import numpy as np
from mathutils import Matrix, Vector

TAU = math.pi * 2.0
ROOT = None


# ── Scene plumbing ────────────────────────────────────────────────────────────

def reset_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.film_transparent = False
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.look = "AgX - Medium High Contrast"
    scene.unit_settings.system = "METRIC"


def mesh_object(name: str, verts, faces) -> bpy.types.Object:
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([tuple(v) for v in verts], [], [tuple(f) for f in faces])
    mesh.validate()
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    return obj


def activate(obj: bpy.types.Object) -> None:
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def smooth(obj: bpy.types.Object, levels: int = 1) -> None:
    activate(obj)
    bpy.ops.object.shade_smooth()
    if levels > 0:
        mod = obj.modifiers.new("Subdivision", "SUBSURF")
        mod.levels = levels
        mod.render_levels = levels


def apply_modifiers(obj: bpy.types.Object) -> None:
    activate(obj)
    for mod in list(obj.modifiers):
        try:
            bpy.ops.object.modifier_apply(modifier=mod.name)
        except RuntimeError:
            obj.modifiers.remove(mod)


def join(objects, name: str):
    objects = [o for o in objects if o is not None]
    if not objects:
        return None
    activate(objects[0])
    for obj in objects[1:]:
        obj.select_set(True)
    bpy.ops.object.join()
    joined = bpy.context.object
    joined.name = name
    joined.data.name = name
    return joined


# ── Geometry building ─────────────────────────────────────────────────────────

def ring_xy(cx: float, cy: float, z: float, rx: float, ry: float,
            segments: int, phase: float = 0.0):
    return [
        (cx + rx * math.cos(TAU * i / segments + phase),
         cy + ry * math.sin(TAU * i / segments + phase), z)
        for i in range(segments)
    ]


def loft(name: str, rings, cap_start=True, cap_end=True) -> bpy.types.Object:
    segments = len(rings[0])
    verts = []
    for ring in rings:
        verts.extend(ring)
    faces = []
    for r in range(len(rings) - 1):
        base = r * segments
        nxt = (r + 1) * segments
        for i in range(segments):
            j = (i + 1) % segments
            faces.append((base + i, base + j, nxt + j, nxt + i))
    if cap_start:
        faces.append(tuple(range(segments - 1, -1, -1)))
    if cap_end:
        base = (len(rings) - 1) * segments
        faces.append(tuple(base + i for i in range(segments)))
    return mesh_object(name, verts, faces)


def tube(name: str, points, radii, segments: int = 24, caps: bool = True,
         bulge: float = 0.0) -> bpy.types.Object:
    """A capsule-ish tube through a polyline, with per-point (rx, ry) radii."""
    pts = [Vector(p) for p in points]
    rings = []
    prev_axis = None
    for index, point in enumerate(pts):
        if index == 0:
            tangent = (pts[1] - pts[0]).normalized()
        elif index == len(pts) - 1:
            tangent = (pts[-1] - pts[-2]).normalized()
        else:
            tangent = (pts[index + 1] - pts[index - 1]).normalized()
        if prev_axis is None:
            reference = Vector((0, 0, 1)) if abs(tangent.z) < 0.88 else Vector((0, 1, 0))
            axis = tangent.cross(reference).normalized()
        else:
            axis = (prev_axis - tangent * prev_axis.dot(tangent))
            if axis.length < 1e-5:
                reference = Vector((0, 0, 1)) if abs(tangent.z) < 0.88 else Vector((0, 1, 0))
                axis = tangent.cross(reference)
            axis.normalize()
        prev_axis = axis
        other = tangent.cross(axis).normalized()
        rx, ry = radii[index]
        ring = []
        for i in range(segments):
            angle = TAU * i / segments
            wobble = 1.0 + bulge * math.sin(angle * 3.0 + index * 0.7)
            offset = axis * (math.cos(angle) * rx * wobble) + other * (math.sin(angle) * ry * wobble)
            ring.append(tuple(point + offset))
        rings.append(ring)
    return loft(name, rings, cap_start=caps, cap_end=caps)


def sphere(name: str, center, scale, segments: int = 96, rings: int = 64) -> bpy.types.Object:
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=rings, radius=1.0,
                                         location=center)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    activate(obj)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    smooth(obj, 0)
    return obj


def rounded_box(name: str, center, scale, bevel: float = 0.02,
                subdiv: int = 1) -> bpy.types.Object:
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    activate(obj)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    mod = obj.modifiers.new("Bevel", "BEVEL")
    mod.width = bevel
    mod.segments = 3
    smooth(obj, subdiv)
    return obj


def displace_noise(obj: bpy.types.Object, scale: float, strength: float,
                   name: str = "Clouds", detail: int = 2) -> None:
    texture = bpy.data.textures.new(f"{obj.name}_{name}", type="CLOUDS")
    texture.noise_scale = scale
    texture.noise_depth = detail
    mod = obj.modifiers.new("Fabric", "DISPLACE")
    mod.texture = texture
    mod.strength = strength
    mod.mid_level = 0.5
    mod.texture_coords = "GLOBAL"


def cloth_folds(obj: bpy.types.Object, strength: float = 0.004, seed: int = 0) -> None:
    """Push creases and compression folds into a fused garment.

    Voxel remeshing leaves cloth looking like shrink-wrapped rubber; real uniforms
    gather in rings at the elbows, knees and waist, and ripple everywhere else.
    """
    mesh = obj.data
    count = len(mesh.vertices)
    if count == 0:
        return
    coords = np.empty(count * 3, dtype=np.float32)
    mesh.vertices.foreach_get("co", coords)
    coords = coords.reshape(-1, 3)
    normals = np.empty(count * 3, dtype=np.float32)
    mesh.vertex_normals.foreach_get("vector", normals)
    normals = normals.reshape(-1, 3)
    x, y, z = coords[:, 0], coords[:, 1], coords[:, 2]
    f = np.sin(z * 61.0 + np.sin(x * 29.0 + y * 19.0) * 1.9)
    g = np.sin((x + y) * 47.0 + z * 13.0)
    h = np.sin(z * 141.0 + x * 23.0 + y * 11.0)
    amount = strength * (0.55 * f + 0.32 * g + 0.13 * h)
    joints = [
        (0.235, 0.005, 1.175, 0.085), (-0.235, 0.005, 1.175, 0.085),
        (0.290, 0.020, 0.965, 0.070), (-0.290, 0.020, 0.965, 0.070),
        (0.110, -0.005, 0.500, 0.095), (-0.110, -0.005, 0.500, 0.095),
        (0.000, 0.000, 1.010, 0.190),
        (0.000, 0.000, 1.390, 0.170),
    ]
    for jx, jy, jz, radius in joints:
        dx, dy, dz = x - jx, y - jy, z - jz
        distance2 = dx * dx + dy * dy + dz * dz
        mask = distance2 < radius * radius
        if not np.any(mask):
            continue
        falloff = 1.0 - np.sqrt(distance2[mask]) / radius
        amount[mask] += (strength * 1.45
                         * np.sin(dz[mask] * 165.0 + dx[mask] * 35.0)
                         * falloff * falloff)
    coords += normals * amount[:, None]
    mesh.vertices.foreach_set("co", coords.ravel())
    mesh.update()


def fuse(objects, name: str, voxel: float = 0.014, smooth_iterations: int = 8,
         subdivide: int = 1) -> bpy.types.Object:
    """Join overlapping volumes and remesh them into one continuous surface.

    The previous version left every limb as its own tube, which is why the
    silhouette read as a doll. Voxel remesh welds the shoulders, hips and
    sleeves into a single skin, and the smooth pass removes the voxel stair
    stepping before subdivision.
    """
    obj = join(objects, name)
    activate(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    remesh = obj.modifiers.new("Remesh", "REMESH")
    remesh.mode = "VOXEL"
    remesh.voxel_size = voxel
    remesh.use_smooth_shade = True
    bpy.ops.object.modifier_apply(modifier=remesh.name)
    smooth_mod = obj.modifiers.new("Relax", "SMOOTH")
    smooth_mod.factor = 0.65
    smooth_mod.iterations = smooth_iterations
    bpy.ops.object.modifier_apply(modifier=smooth_mod.name)
    if subdivide:
        sub = obj.modifiers.new("Subdivision", "SUBSURF")
        sub.levels = subdivide
        sub.render_levels = subdivide
    activate(obj)
    bpy.ops.object.shade_smooth()
    return obj


def rough_ellipsoid(name: str, center, scale, noise: float = 0.3,
                    seed: int = 0) -> bpy.types.Object:
    obj = sphere(name, center, scale, segments=48, rings=32)
    rng = random.Random(seed)
    mesh = obj.data
    for vertex in mesh.vertices:
        # Local coordinates: the object already carries `center` as its location.
        factor = 1.0 + (rng.random() - 0.5) * noise
        vertex.co = vertex.co * factor
    mesh.update()
    return obj


# ── Generated textures ───────────────────────────────────────────────────────

def periodic_noise(size: int, cells: int, rng: np.random.Generator) -> np.ndarray:
    grid = rng.random((cells, cells)).astype(np.float32)
    ys = np.linspace(0, cells, size, endpoint=False)
    xs = np.linspace(0, cells, size, endpoint=False)
    y0 = np.floor(ys).astype(np.int32)
    x0 = np.floor(xs).astype(np.int32)
    fy = (ys - y0)[:, None]
    fx = (xs - x0)[None, :]
    y1 = (y0 + 1) % cells
    x1 = (x0 + 1) % cells
    a = grid[np.ix_(y0, x0)]
    b = grid[np.ix_(y0, x1)]
    c = grid[np.ix_(y1, x0)]
    d = grid[np.ix_(y1, x1)]
    sx = fx * fx * (3 - 2 * fx)
    sy = fy * fy * (3 - 2 * fy)
    top = a + (b - a) * sx
    bottom = c + (d - c) * sx
    return top + (bottom - top) * sy


def fbm(size: int, rng: np.random.Generator, base: int = 4, octaves: int = 6,
        persistence: float = 0.55) -> np.ndarray:
    total = np.zeros((size, size), np.float32)
    amplitude = 1.0
    norm = 0.0
    cells = base
    for _ in range(octaves):
        total += periodic_noise(size, cells, rng) * amplitude
        norm += amplitude
        amplitude *= persistence
        cells *= 2
    return total / norm


def normal_from_height(height: np.ndarray, strength: float = 2.0) -> np.ndarray:
    dx = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) * strength
    dy = (np.roll(height, -1, axis=0) - np.roll(height, 1, axis=0)) * strength
    nz = np.ones_like(height)
    length = np.sqrt(dx * dx + dy * dy + 1.0)
    normal = np.stack((-dx / length, -dy / length, nz / length), axis=-1)
    return np.clip(normal * 0.5 + 0.5, 0.0, 1.0)


def write_png(path: str, data: np.ndarray) -> str:
    """Write RGB8 PNG directly; Blender's image buffer is unreliable for generated pixels."""
    rgb = (np.clip(data[..., :3], 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
    height, width = rgb.shape[:2]
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(rgb[y].tobytes())

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n")
        handle.write(chunk(b"IHDR", header))
        handle.write(chunk(b"IDAT", zlib.compress(bytes(raw), 6)))
        handle.write(chunk(b"IEND", b""))
    return os.path.abspath(path)


def save_image(name: str, data: np.ndarray, path: str, non_color: bool = False) -> str:
    return write_png(path, data)


def generate_textures(out_dir: str, seed: int = 11) -> dict[str, str]:
    """Skin, cloth, leather and hair maps generated with numpy, no external images."""
    os.makedirs(out_dir, exist_ok=True)
    rng = np.random.default_rng(seed)
    size = 2048
    paths: dict[str, str] = {}

    # ── Skin ────────────────────────────────────────────────────────────────
    mottle = fbm(size, rng, base=3, octaves=7)
    fine = fbm(size, rng, base=48, octaves=4, persistence=0.6)
    pores = fbm(size, rng, base=180, octaves=3, persistence=0.7)
    vascular = fbm(size, rng, base=9, octaves=5)
    veins = np.abs(vascular - 0.5)
    vein_line = np.clip(1.0 - veins / 0.018, 0.0, 1.0) * (vascular < 0.5)
    dirt = np.clip((fbm(size, rng, base=5, octaves=5) - 0.56) / 0.2, 0.0, 1.0)

    pale = np.array([0.34, 0.33, 0.27], np.float32)
    bruised = np.array([0.16, 0.10, 0.12], np.float32)
    necrotic = np.array([0.20, 0.22, 0.14], np.float32)
    blotch = fbm(size, rng, base=2, octaves=5)
    albedo = pale[None, None, :] * (0.50 + 0.55 * mottle[..., None])
    albedo = albedo * (1 - 0.38 * np.clip(mottle[..., None] - 0.55, 0, 1))
    albedo += (bruised - pale)[None, None, :] * np.clip((vascular - 0.50) * 3.0, 0, 1)[..., None] * 0.65
    albedo += (necrotic - pale)[None, None, :] * np.clip((0.45 - mottle) * 2.0, 0, 1)[..., None] * 0.65
    albedo *= 0.82 + 0.38 * blotch[..., None]
    albedo *= 1.0 - dirt[..., None] * 0.42
    albedo = albedo * (0.90 + 0.18 * fine[..., None]) * (0.94 + 0.10 * pores[..., None])
    albedo = albedo * (1 - 0.55 * vein_line[..., None])
    albedo += np.array([0.10, 0.02, 0.02], np.float32)[None, None, :] * np.clip(vein_line * 1.5, 0, 1)[..., None]
    save_image("SkinAlbedo", np.clip(albedo, 0, 1), os.path.join(out_dir, "skin_albedo.png"))
    paths["skin_albedo"] = os.path.join(out_dir, "skin_albedo.png")

    height = 0.55 * mottle + 0.25 * fine + 0.12 * pores + 0.08 * (1 - vein_line)
    normal = normal_from_height(height, 3.2)
    save_image("SkinNormal", normal, os.path.join(out_dir, "skin_normal.png"), True)
    paths["skin_normal"] = os.path.join(out_dir, "skin_normal.png")
    rough = 0.58 + 0.18 * fine - 0.18 * np.clip(vein_line * 1.5, 0, 1) + 0.10 * dirt
    save_image("SkinRough", np.repeat(rough[..., None], 3, axis=2), os.path.join(out_dir, "skin_rough.png"), True)
    paths["skin_rough"] = os.path.join(out_dir, "skin_rough.png")

    # ── Cloth ───────────────────────────────────────────────────────────────
    weave_x = (np.sin(np.linspace(0, 2400, size)) * 0.5 + 0.5)[None, :]
    weave_y = (np.sin(np.linspace(0, 2400, size)) * 0.5 + 0.5)[:, None]
    weave = (weave_x * 0.6 + weave_y * 0.6 + np.roll(weave_x, 3) * 0.3) / 1.5
    grime = fbm(size, rng, base=4, octaves=6)
    blood_noise = fbm(size, rng, base=6, octaves=5)
    blood = np.clip((blood_noise - 0.62) / 0.10, 0, 1)
    wear = np.clip((fbm(size, rng, base=20, octaves=4) - 0.55) / 0.2, 0, 1)

    olive = np.array([0.128, 0.142, 0.080], np.float32)
    cloth = olive[None, None, :] * (0.76 + 0.40 * grime[..., None])
    cloth = cloth * (0.86 + 0.22 * weave[..., None])
    cloth *= 1.0 - wear[..., None] * 0.22
    cloth *= 1.0 - blood[..., None] * 0.62
    cloth += np.array([0.085, 0.007, 0.005], np.float32)[None, None, :] * blood[..., None] * 0.9
    save_image("ClothAlbedo", np.clip(cloth, 0, 1), os.path.join(out_dir, "cloth_albedo.png"))
    paths["cloth_albedo"] = os.path.join(out_dir, "cloth_albedo.png")
    cloth_height = weave * 0.35 + grime * 0.35 + wear * 0.15
    save_image("ClothNormal", normal_from_height(cloth_height, 2.2),
               os.path.join(out_dir, "cloth_normal.png"), True)
    paths["cloth_normal"] = os.path.join(out_dir, "cloth_normal.png")
    cloth_rough = 0.82 + 0.12 * wear - 0.30 * blood
    save_image("ClothRough", np.repeat(cloth_rough[..., None], 3, axis=2),
               os.path.join(out_dir, "cloth_rough.png"), True)
    paths["cloth_rough"] = os.path.join(out_dir, "cloth_rough.png")

    # ── Leather ─────────────────────────────────────────────────────────────
    lsize = 1024
    grain = fbm(lsize, rng, base=40, octaves=5)
    scuff = np.clip((fbm(lsize, rng, base=7, octaves=5) - 0.58) / 0.18, 0, 1)
    leather = np.array([0.105, 0.078, 0.052], np.float32)[None, None, :] * (0.65 + 0.75 * grain[..., None])
    leather += np.array([0.09, 0.07, 0.05], np.float32)[None, None, :] * scuff[..., None] * 0.5
    save_image("LeatherAlbedo", np.clip(leather, 0, 1), os.path.join(out_dir, "leather_albedo.png"))
    paths["leather_albedo"] = os.path.join(out_dir, "leather_albedo.png")
    save_image("LeatherNormal", normal_from_height(grain * 0.6 + scuff * 0.2, 2.0),
               os.path.join(out_dir, "leather_normal.png"), True)
    paths["leather_normal"] = os.path.join(out_dir, "leather_normal.png")

    # ── Hair ────────────────────────────────────────────────────────────────
    strands = np.zeros((lsize, lsize), np.float32)
    for _ in range(320):
        x = rng.integers(0, lsize)
        phase = rng.random() * TAU
        thickness = rng.integers(1, 4)
        for y in range(lsize):
            offset = int(6 * math.sin(y / 60.0 + phase))
            xs = (x + offset) % lsize
            strands[y, max(0, xs - thickness):min(lsize, xs + thickness)] = 1.0
    streak = fbm(lsize, rng, base=12, octaves=5)
    hair = np.array([0.040, 0.026, 0.016], np.float32)[None, None, :] * (0.6 + 0.7 * streak[..., None])
    hair = hair * (0.55 + 0.65 * strands[..., None])
    save_image("HairAlbedo", np.clip(hair, 0, 1), os.path.join(out_dir, "hair_albedo.png"))
    paths["hair_albedo"] = os.path.join(out_dir, "hair_albedo.png")
    return {name: os.path.abspath(path) for name, path in paths.items()}


def image_material(name: str, textures: dict[str, str], albedo_key: str, normal_key: str,
                   rough_key: str | None = None, color=(1, 1, 1), sss: float = 0.0,
                   metallic: float = 0.0, uv_scale: float = 1.0) -> bpy.types.Material:
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*color, 1)
    bsdf.inputs["Roughness"].default_value = 0.6
    bsdf.inputs["Metallic"].default_value = metallic
    if "Subsurface Weight" in bsdf.inputs:
        bsdf.inputs["Subsurface Weight"].default_value = sss
        bsdf.inputs["Subsurface Radius"].default_value = (0.35, 0.12, 0.08)
    uv = nodes.new("ShaderNodeUVMap")
    mapping = nodes.new("ShaderNodeMapping")
    mapping.inputs["Scale"].default_value = (uv_scale, uv_scale, uv_scale)
    links.new(uv.outputs["UV"], mapping.inputs["Vector"])
    albedo = nodes.new("ShaderNodeTexImage")
    albedo.image = bpy.data.images.load(textures[albedo_key], check_existing=True)
    links.new(mapping.outputs["Vector"], albedo.inputs["Vector"])
    links.new(albedo.outputs["Color"], bsdf.inputs["Base Color"])
    if normal_key in textures:
        normal_tex = nodes.new("ShaderNodeTexImage")
        normal_tex.image = bpy.data.images.load(textures[normal_key], check_existing=True)
        normal_tex.image.colorspace_settings.name = "Non-Color"
        links.new(mapping.outputs["Vector"], normal_tex.inputs["Vector"])
        normal_map = nodes.new("ShaderNodeNormalMap")
        normal_map.inputs["Strength"].default_value = 1.0
        links.new(normal_tex.outputs["Color"], normal_map.inputs["Color"])
        links.new(normal_map.outputs["Normal"], bsdf.inputs["Normal"])
    if rough_key and rough_key in textures:
        rough_tex = nodes.new("ShaderNodeTexImage")
        rough_tex.image = bpy.data.images.load(textures[rough_key], check_existing=True)
        rough_tex.image.colorspace_settings.name = "Non-Color"
        links.new(mapping.outputs["Vector"], rough_tex.inputs["Vector"])
        links.new(rough_tex.outputs["Color"], bsdf.inputs["Roughness"])
    return mat


def uv_unwrap(obj: bpy.types.Object) -> None:
    activate(obj)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.015)
    bpy.ops.object.mode_set(mode="OBJECT")


# ── Materials ─────────────────────────────────────────────────────────────────

def new_material(name: str, base, roughness: float = 0.65, metallic: float = 0.0,
                 sss: float = 0.0, sss_radius=(0.4, 0.12, 0.08),
                 sheen: float = 0.0) -> bpy.types.Material:
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*base, 1.0)
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    if "Subsurface Weight" in bsdf.inputs:
        bsdf.inputs["Subsurface Weight"].default_value = sss
        bsdf.inputs["Subsurface Radius"].default_value = sss_radius
    if "Sheen Weight" in bsdf.inputs:
        bsdf.inputs["Sheen Weight"].default_value = sheen
    return mat


def skin_material() -> bpy.types.Material:
    """Necrotic skin: mottled grey-green, bruised purple, dried blood, fine pores."""
    mat = bpy.data.materials.new("ZombieSkin")
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = 0.58
    bsdf.inputs["Subsurface Weight"].default_value = 0.22
    bsdf.inputs["Subsurface Radius"].default_value = (0.35, 0.10, 0.07)
    bsdf.inputs["Specular IOR Level"].default_value = 0.35

    tex = nodes.new("ShaderNodeTexNoise")
    tex.inputs["Scale"].default_value = 5.5
    tex.inputs["Detail"].default_value = 10.0
    tex.inputs["Roughness"].default_value = 0.72
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.30
    ramp.color_ramp.elements[0].color = (0.105, 0.125, 0.075, 1)
    ramp.color_ramp.elements[1].position = 0.72
    ramp.color_ramp.elements[1].color = (0.46, 0.44, 0.33, 1)
    mid = ramp.color_ramp.elements.new(0.52)
    mid.color = (0.24, 0.25, 0.17, 1)
    bruise = ramp.color_ramp.elements.new(0.40)
    bruise.color = (0.20, 0.11, 0.14, 1)
    links.new(tex.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])

    # Pores and creases.
    pores = nodes.new("ShaderNodeTexNoise")
    pores.inputs["Scale"].default_value = 240.0
    pores.inputs["Detail"].default_value = 4.0
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.35
    bump.inputs["Distance"].default_value = 0.003
    links.new(pores.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])

    # Broken capillaries.
    veins = nodes.new("ShaderNodeTexWave")
    veins.wave_type = "BANDS"
    veins.bands_direction = "DIAGONAL"
    veins.inputs["Scale"].default_value = 7.0
    veins.inputs["Distortion"].default_value = 13.0
    veins.inputs["Detail"].default_value = 3.0
    vramp = nodes.new("ShaderNodeValToRGB")
    vramp.color_ramp.elements[0].position = 0.44
    vramp.color_ramp.elements[0].color = (0, 0, 0, 1)
    vramp.color_ramp.elements[1].position = 0.50
    vramp.color_ramp.elements[1].color = (0.16, 0.02, 0.03, 1)
    links.new(veins.outputs["Fac"], vramp.inputs["Fac"])
    mix = nodes.new("ShaderNodeMixRGB")
    mix.blend_type = "MULTIPLY"
    mix.inputs["Fac"].default_value = 0.55
    links.new(ramp.outputs["Color"], mix.inputs["Color1"])
    links.new(vramp.outputs["Color"], mix.inputs["Color2"])
    links.new(mix.outputs["Color"], bsdf.inputs["Base Color"])
    return mat


def cloth_material(name: str, base, dirt=0.55) -> bpy.types.Material:
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = 0.88
    bsdf.inputs["Sheen Weight"].default_value = 0.35
    bsdf.inputs["Base Color"].default_value = (*base, 1)

    grime = nodes.new("ShaderNodeTexNoise")
    grime.inputs["Scale"].default_value = 8.0
    grime.inputs["Detail"].default_value = 9.0
    ramp = nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.30
    ramp.color_ramp.elements[0].color = (base[0] * 0.45, base[1] * 0.42, base[2] * 0.38, 1)
    ramp.color_ramp.elements[1].position = 0.70
    ramp.color_ramp.elements[1].color = (base[0] * 1.22, base[1] * 1.18, base[2] * 1.10, 1)
    links.new(grime.outputs["Fac"], ramp.inputs["Fac"])
    links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])

    weave = nodes.new("ShaderNodeTexWave")
    weave.wave_type = "BANDS"
    weave.inputs["Scale"].default_value = 420.0
    weave.inputs["Distortion"].default_value = 1.2
    bump = nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.42
    bump.inputs["Distance"].default_value = 0.0018
    links.new(weave.outputs["Fac"], bump.inputs["Height"])
    links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])

    # Blood soaked into the lower legs and cuffs.
    blood = nodes.new("ShaderNodeTexNoise")
    blood.inputs["Scale"].default_value = 3.2
    blood.inputs["Detail"].default_value = 6.0
    bramp = nodes.new("ShaderNodeValToRGB")
    bramp.color_ramp.elements[0].position = 0.58
    bramp.color_ramp.elements[0].color = (0, 0, 0, 1)
    bramp.color_ramp.elements[1].position = 0.64
    bramp.color_ramp.elements[1].color = (0.22, 0.015, 0.012, 1)
    links.new(blood.outputs["Fac"], bramp.inputs["Fac"])
    bmix = nodes.new("ShaderNodeMixRGB")
    bmix.blend_type = "MIX"
    bmix.inputs["Fac"].default_value = dirt
    links.new(ramp.outputs["Color"], bmix.inputs["Color1"])
    links.new(bramp.outputs["Color"], bmix.inputs["Color2"])
    links.new(bmix.outputs["Color"], bsdf.inputs["Base Color"])
    return mat


def simple_material(name: str, base, rough=0.6, metal=0.0, sss=0.0) -> bpy.types.Material:
    mat = new_material(name, base, rough, metal, sss)
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    noise = mat.node_tree.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 120.0
    noise.inputs["Detail"].default_value = 3.0
    bump = mat.node_tree.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.25
    bump.inputs["Distance"].default_value = 0.001
    mat.node_tree.links.new(noise.outputs["Fac"], bump.inputs["Height"])
    mat.node_tree.links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])
    return mat


# ── Anatomy ──────────────────────────────────────────────────────────────────

def build_head(male: bool, seed: int) -> list[bpy.types.Object]:
    rng = random.Random(seed)
    parts = []
    head = sphere("Head", (0, -0.012, 1.66), (0.092, 0.108, 0.127), 128, 96)
    mesh = head.data
    for vertex in mesh.vertices:
        co = vertex.co.copy()
        # Local coordinates around the head centre.
        x = (co.x - 0.0) / 0.092
        y = (co.y + 0.012) / 0.108
        z = (co.z - 1.66) / 0.127
        # Narrow the jaw and chin.
        taper = max(0.0, min(1.0, (-z + 0.05) / 1.05))
        x *= 1.0 - 0.30 * taper * taper
        # Flatten the back and push the face forward.
        if y > 0:
            y *= 0.92
        if y < 0:
            y -= 0.03 * max(0.0, 0.4 - abs(z + 0.05)) * max(0.0, -y)
        # Brow ridge.
        brow = math.exp(-((z - 0.30) / 0.13) ** 2) * max(0.0, -y)
        y -= 0.055 * brow
        # Cheekbones.
        cheek = math.exp(-(((abs(x) - 0.62) / 0.22) ** 2) - (((z + 0.02) / 0.20) ** 2))
        y -= 0.035 * cheek * max(0.0, -y)
        # Sunken eye sockets.
        for side in (-1, 1):
            dx = (x - side * 0.42) / 0.22
            dz = (z - 0.16) / 0.16
            socket = math.exp(-(dx * dx) - (dz * dz))
            if y < 0:
                y += 0.10 * socket * (-y)
        # Mouth crease and chin.
        mouth = math.exp(-((z + 0.35) / 0.09) ** 2) * max(0.0, -y)
        y += 0.035 * mouth
        chin = math.exp(-(((x) / 0.32) ** 2) - (((z + 0.72) / 0.16) ** 2))
        y -= 0.025 * chin
        # Neck transition at the base: pull the back of the skull in.
        if z < -0.75:
            neck = min(1.0, (-z - 0.75) / 0.25)
            y *= 1.0 - 0.45 * neck
            x *= 1.0 + 0.20 * neck
        vertex.co = Vector((x * 0.092, y * 0.108, z * 0.127 + 1.66))
    mesh.update()
    parts.append(head)

    # Eye sockets: dark cavities and wet eyeballs.
    socket_mat = simple_material("Socket", (0.015, 0.008, 0.006), 0.35)
    eye_white = new_material("EyeSclera", (0.52, 0.47, 0.32), 0.18, sss=0.1)
    iris_mat = new_material("EyeIris", (0.16, 0.07, 0.03), 0.30)
    pupil_mat = new_material("Pupil", (0.004, 0.003, 0.003), 0.22)
    for side in (-1, 1):
        cavity = sphere(f"Socket{side}", (side * 0.040, -0.070, 1.685),
                        (0.027, 0.026, 0.024), 48, 32)
        cavity.data.materials.append(socket_mat)
        parts.append(cavity)
        eye = sphere(f"Eye{side}", (side * 0.040, -0.0915, 1.686),
                     (0.0145, 0.0145, 0.0145), 64, 48)
        eye.data.materials.append(eye_white)
        parts.append(eye)
        iris = sphere(f"Iris{side}", (side * 0.040, -0.1045, 1.686),
                      (0.0078, 0.0024, 0.0078), 48, 32)
        iris.data.materials.append(iris_mat)
        parts.append(iris)
        pupil = sphere(f"Pupil{side}", (side * 0.040, -0.1065, 1.686),
                       (0.0032, 0.0016, 0.0032), 32, 24)
        pupil.data.materials.append(pupil_mat)
        parts.append(pupil)
        # Eyelid rim: a thin skin torus that keeps the eyeball inside the face.
        bpy.ops.mesh.primitive_torus_add(
            major_radius=0.0165, minor_radius=0.0038,
            location=(side * 0.040, -0.0995, 1.686),
            rotation=(math.pi / 2, 0, 0),
        )
        lid = bpy.context.object
        lid.name = f"Eyelid{side}"
        lid.scale = (1.0, 1.0, 0.82)
        smooth(lid, 1)
        lid.data.materials.append(skin_material())
        parts.append(lid)
        # Brow ridge over the socket.
        brow = tube(f"Brow{side}",
                    [(side * 0.012, -0.101, 1.716), (side * 0.040, -0.104, 1.722),
                     (side * 0.066, -0.094, 1.712)],
                    [(0.008, 0.007), (0.010, 0.008), (0.007, 0.006)], 12)
        brow.data.materials.append(skin_material())
        parts.append(brow)

    # Nose: a wedge lofted over the face with nostril dents.
    nose = tube("Nose", [(0, -0.090, 1.706), (0, -0.108, 1.672), (0, -0.116, 1.648)],
                [(0.013, 0.011), (0.020, 0.016), (0.023, 0.015)], 20)
    nose.data.materials.append(skin_material())
    parts.append(nose)
    for side in (-1, 1):
        nostril = sphere(f"Nostril{side}", (side * 0.0105, -0.1125, 1.6455),
                         (0.0055, 0.004, 0.0045), 20, 16)
        nostril.data.materials.append(socket_mat)
        parts.append(nostril)

    # Mouth cavity, gums and teeth.
    cavity = rounded_box("MouthCavity", (0, -0.060, 1.615), (0.060, 0.038, 0.030),
                         bevel=0.02, subdiv=1)
    cavity.data.materials.append(socket_mat)
    parts.append(cavity)
    teeth_mat = new_material("Teeth", (0.44, 0.37, 0.22), 0.32)
    gums_mat = new_material("Gums", (0.18, 0.035, 0.03), 0.42)
    lip_mat = new_material("Lips", (0.115, 0.048, 0.044), 0.46)
    for row in (-1, 1):
        gum = rounded_box(f"Gums{row}", (0, -0.090, 1.615 + row * 0.021),
                          (0.054, 0.014, 0.010), 0.006, 1)
        gum.data.materials.append(gums_mat)
        parts.append(gum)
        for i in range(8):
            tx = -0.044 + i * 0.0126
            tooth = rounded_box(f"Tooth{row}_{i}", (tx, -0.092, 1.615 + row * 0.024),
                                (0.0068, 0.005, 0.010 + (rng.random() * 0.004)),
                                0.002, 1)
            tooth.data.materials.append(teeth_mat)
            parts.append(tooth)
    # Lips: two thin tubes, parted around the exposed teeth.
    for row, z in ((-1, 1.638), (1, 1.596)):
        lip = tube(f"Lip{row}",
                   [(-0.046, -0.098, z), (-0.024, -0.105, z + row * 0.002),
                    (0.0, -0.107, z), (0.024, -0.105, z + row * 0.002),
                    (0.046, -0.098, z)],
                   [(0.004, 0.004), (0.007, 0.006), (0.008, 0.006),
                    (0.007, 0.006), (0.004, 0.004)], 12)
        lip.data.materials.append(lip_mat)
        parts.append(lip)

    # Ears: flattened shells.
    ear_mat = new_material("EarSkin", (0.22, 0.18, 0.13), 0.55, sss=0.15)
    for side in (-1, 1):
        ear = sphere(f"Ear{side}", (side * 0.092, -0.005, 1.665),
                     (0.012, 0.023, 0.034), 48, 32)
        ear.data.materials.append(ear_mat)
        parts.append(ear)

    # Neck.
    neck = tube("Neck", [(0, 0.0, 1.50), (0, 0.002, 1.58), (0, -0.005, 1.62)],
                [(0.055, 0.050), (0.052, 0.047), (0.048, 0.044)], 32)
    neck.data.materials.append(skin_material())
    parts.append(neck)

    # Hair for the female variant: long, wet, clinging strands.
    if not male:
        parts.extend(build_hair(seed))
    else:
        # Male: a few surviving patches of stubble on the scalp, thin and dark.
        scalp = sphere("Stubble", (0, 0.0, 1.705), (0.090, 0.103, 0.112), 96, 64)
        scalp.data.materials.append(simple_material("StubbleMat", (0.06, 0.05, 0.04), 0.75))
        parts.append(scalp)

    # Necrotic wounds on the face and neck.
    blood = blood_material()
    for i, (center, scale) in enumerate([
        ((0.045, -0.094, 1.735), (0.030, 0.012, 0.022)),
        ((-0.055, -0.086, 1.60), (0.024, 0.010, 0.020)),
        ((0.02, -0.078, 1.55), (0.020, 0.008, 0.024)),
    ]):
        wound = rough_ellipsoid(f"FaceWound{i}", center, scale, 0.25, seed + i)
        wound.data.materials.append(blood)
        parts.append(wound)

    return parts


def head_ring(z: float, rx: float, front: float, back: float, segments: int = 64, cy: float = 0.0):
    points = []
    for i in range(segments):
        a = TAU * i / segments
        s = math.sin(a)
        depth = front if s < 0 else back
        points.append((rx * math.cos(a), cy + depth * s, z))
    return points


def build_head_v3(male: bool, seed: int) -> list[bpy.types.Object]:
    """A head lofted from anatomical cross-sections instead of fused balls."""
    rng = random.Random(seed)
    skin = skin_material()
    parts: list[bpy.types.Object] = []
    rings = [
        head_ring(1.485, 0.057, 0.052, 0.057),
        head_ring(1.520, 0.052, 0.056, 0.060),
        head_ring(1.548, 0.040, 0.090, 0.064),
        head_ring(1.570, 0.052, 0.092, 0.072),
        head_ring(1.590, 0.061, 0.086, 0.078),
        head_ring(1.612, 0.064, 0.082, 0.082),
        head_ring(1.632, 0.067, 0.086, 0.085),
        head_ring(1.652, 0.073, 0.092, 0.088),
        head_ring(1.675, 0.078, 0.089, 0.091),
        head_ring(1.700, 0.080, 0.103, 0.094),
        head_ring(1.722, 0.077, 0.100, 0.096),
        head_ring(1.748, 0.070, 0.090, 0.094),
        head_ring(1.770, 0.058, 0.076, 0.086),
        head_ring(1.788, 0.037, 0.052, 0.064),
        head_ring(1.798, 0.012, 0.020, 0.028),
    ]
    head = loft("Head", rings)
    smooth(head, 2)
    apply_modifiers(head)
    smooth(head, 1)
    apply_modifiers(head)

    # Cut a real mouth opening instead of painting one on.
    cutter = sphere("MouthCut", (0, -0.092, 1.611), (0.044, 0.052, 0.026), 48, 32)
    activate(head)
    boolean = head.modifiers.new("Mouth", "BOOLEAN")
    boolean.operation = "DIFFERENCE"
    boolean.object = cutter
    boolean.solver = "EXACT"
    bpy.ops.object.modifier_apply(modifier=boolean.name)
    bpy.data.objects.remove(cutter, do_unlink=True)
    activate(head)
    bpy.ops.object.shade_smooth()

    # Sculpt the landmarks the loft cannot express.
    for vertex in head.data.vertices:
        co = vertex.co
        # Brow ridge.
        brow = math.exp(-(((co.z - 1.712) / 0.014) ** 2)) * max(0.0, (-co.y - 0.075) / 0.03)
        if co.y < -0.070:
            co.y -= 0.007 * brow
        # Cheekbones.
        for side in (-1, 1):
            d = ((co.x - side * 0.052) ** 2 + (co.z - 1.648) ** 2) ** 0.5
            if co.y < -0.060 and d < 0.030:
                co.y -= 0.005 * (1 - d / 0.030)
            # Temple hollows.
            if abs(co.x) > 0.060 and 1.690 < co.z < 1.740 and co.y < 0:
                co.x *= 0.985
        # Eye sockets.
        for side in (-1, 1):
            centre = Vector((side * 0.034, -0.092, 1.688))
            distance = (co - centre).length
            if distance < 0.032:
                co += (centre - co).normalized() * 0.022 * (1 - distance / 0.032) ** 1.35
        # Flatten the back of the skull slightly.
        if co.y > 0.070:
            co.y = 0.070 + (co.y - 0.070) * 0.9
    head.data.update()
    head.data.materials.append(skin)
    parts.append(head)

    socket_mat = simple_material("Socket", (0.010, 0.005, 0.004), 0.40)
    eye_white = new_material("EyeSclera", (0.46, 0.40, 0.26), 0.14, sss=0.1)
    iris_mat = new_material("EyeIris", (0.13, 0.045, 0.018), 0.26)
    pupil_mat = new_material("Pupil", (0.002, 0.002, 0.002), 0.18)
    for side in (-1, 1):
        socket = sphere(f"SocketCavity{side}", (side * 0.034, -0.079, 1.688),
                        (0.025, 0.022, 0.023), 48, 32)
        socket.data.materials.append(socket_mat)
        parts.append(socket)
        eye = sphere(f"Eye{side}", (side * 0.034, -0.0872, 1.688),
                     (0.0122, 0.0122, 0.0122), 64, 48)
        eye.data.materials.append(eye_white)
        parts.append(eye)
        iris = sphere(f"Iris{side}", (side * 0.034, -0.0982, 1.688),
                      (0.0064, 0.0020, 0.0064), 48, 32)
        iris.data.materials.append(iris_mat)
        parts.append(iris)
        pupil = sphere(f"Pupil{side}", (side * 0.034, -0.0996, 1.688),
                       (0.0027, 0.0013, 0.0027), 32, 24)
        pupil.data.materials.append(pupil_mat)
        parts.append(pupil)
        # Heavy upper lid, receding lower lid: the eye becomes a slit under a hood.
        lid = sphere(f"UpperLid{side}", (side * 0.034, -0.092, 1.697),
                     (0.018, 0.010, 0.010), 48, 32)
        lid.data.materials.append(skin)
        parts.append(lid)
        lower = sphere(f"LowerLid{side}", (side * 0.034, -0.0918, 1.678),
                       (0.017, 0.009, 0.0055), 48, 32)
        lower.data.materials.append(skin)
        parts.append(lower)
        crease = tube(f"LidCrease{side}",
                      [(side * 0.017, -0.094, 1.7025), (side * 0.034, -0.0975, 1.7055),
                       (side * 0.051, -0.089, 1.701)],
                      [(0.0020, 0.0016), (0.0025, 0.0020), (0.0016, 0.0013)], 8)
        crease.data.materials.append(simple_material(f"Crease{side}", (0.09, 0.065, 0.052), 0.6))
        parts.append(crease)

    # Nose: bridge, tip and two alae.
    nose = tube("NoseBridge",
                [(0, -0.090, 1.730), (0, -0.100, 1.702), (0, -0.112, 1.674),
                 (0, -0.118, 1.655)],
                [(0.008, 0.008), (0.010, 0.010), (0.015, 0.013), (0.018, 0.014)], 24)
    nose.data.materials.append(skin)
    parts.append(nose)
    for side in (-1, 1):
        ala = sphere(f"Ala{side}", (side * 0.016, -0.108, 1.652),
                     (0.0115, 0.010, 0.0085), 32, 24)
        ala.data.materials.append(skin)
        parts.append(ala)
        nostril = sphere(f"Nostril{side}", (side * 0.010, -0.1125, 1.6455),
                         (0.0052, 0.0035, 0.0043), 20, 16)
        nostril.data.materials.append(socket_mat)
        parts.append(nostril)

    # Mouth: the head has a real opening; a dark cavity and jagged teeth sit inside it.
    cavity = rounded_box("MouthCavity", (0, -0.048, 1.612), (0.050, 0.040, 0.028), 0.02, 1)
    cavity.data.materials.append(socket_mat)
    parts.append(cavity)
    teeth_mat = new_material("Teeth", (0.40, 0.33, 0.19), 0.30)
    gums_mat = new_material("Gums", (0.15, 0.025, 0.022), 0.42)
    lip_mat = new_material("Lips", (0.095, 0.040, 0.036), 0.42)
    for row in (-1, 1):
        gum = rounded_box(f"Gums{row}", (0, -0.084, 1.612 + row * 0.019),
                          (0.048, 0.012, 0.0085), 0.005, 1)
        gum.data.materials.append(gums_mat)
        parts.append(gum)
        for i in range(8):
            tx = -0.039 + i * 0.0112
            tooth = rounded_box(f"Tooth{row}_{i}", (tx, -0.086, 1.612 + row * 0.021),
                                (0.0060, 0.0042, 0.0090 + rng.random() * 0.0030), 0.002, 1)
            tooth.data.materials.append(teeth_mat)
            parts.append(tooth)
    # Torn lip edges: irregular skin fragments, not two perfect sausages.
    for i in range(8):
        angle = math.pi * (i / 7.0)
        x = -0.046 + 0.092 * angle / math.pi
        upper = rough_ellipsoid(f"LipTatter{i}",
                                (x, -0.089, 1.632 + 0.004 * math.sin(angle * 3)),
                                (0.008, 0.005, 0.006), 0.5, seed + i)
        upper.data.materials.append(lip_mat)
        parts.append(upper)
        lower = rough_ellipsoid(f"LipTatterB{i}",
                                (x, -0.088, 1.590 + 0.003 * math.cos(angle * 2)),
                                (0.007, 0.0045, 0.005), 0.5, seed + i + 20)
        lower.data.materials.append(lip_mat)
        parts.append(lower)

    # Ears with a helix rim and a concha.
    ear_mat = new_material("EarSkin", (0.17, 0.14, 0.10), 0.55, sss=0.15)
    for side in (-1, 1):
        ear = sphere(f"Ear{side}", (side * 0.078, 0.002, 1.668),
                     (0.008, 0.017, 0.028), 48, 32)
        ear.data.materials.append(ear_mat)
        parts.append(ear)
        concha = sphere(f"Concha{side}", (side * 0.084, -0.005, 1.668),
                        (0.0035, 0.009, 0.014), 32, 24)
        concha.data.materials.append(simple_material(f"ConchaMat{side}", (0.055, 0.030, 0.022), 0.6))
        parts.append(concha)

    if not male:
        parts.extend(build_hair(seed))
    else:
        for i in range(6):
            angle = rng.random() * TAU
            patch = rough_ellipsoid(f"ScalpPatch{i}",
                                    (0.055 * math.cos(angle), 0.02 + 0.055 * math.sin(angle), 1.782),
                                    (0.020, 0.016, 0.0035), 0.4, seed + i)
            patch.data.materials.append(simple_material(f"Stubble{i}", (0.030, 0.026, 0.021), 0.85))
            parts.append(patch)

    blood = blood_material()
    for i, (center, scale) in enumerate([
        ((0.050, -0.086, 1.742), (0.022, 0.004, 0.017)),
        ((-0.058, -0.080, 1.604), (0.016, 0.0035, 0.014)),
        ((0.014, -0.077, 1.566), (0.014, 0.003, 0.017)),
        ((-0.068, -0.068, 1.674), (0.011, 0.003, 0.022)),
    ]):
        wound = rough_ellipsoid(f"FaceWound{i}", center, scale, 0.22, seed + i)
        wound.data.materials.append(blood)
        parts.append(wound)
    return parts


def meta_element(mb, co, size, stiffness: float = 2.0, negative: bool = False):
    element = mb.elements.new(type="ELLIPSOID")
    element.co = co
    element.radius = 1.0
    element.size_x, element.size_y, element.size_z = size
    element.stiffness = stiffness
    element.use_negative = negative
    return element


def surface_patch(name: str, target, center, radius, material, seed: int = 0,
                  squash=(1.0, 1.0), offset: float = 0.0012) -> bpy.types.Object:
    """A decal that shrink-wraps to the body instead of floating in front of it."""
    segments = 48
    mesh = bpy.data.meshes.new(name)
    verts = [(0.0, 0.0, 0.0)]
    rng = random.Random(seed)
    for i in range(segments):
        angle = TAU * i / segments
        wobble = 1.0 + (rng.random() - 0.5) * 0.30
        verts.append((math.cos(angle) * radius * squash[0] * wobble,
                      math.sin(angle) * radius * squash[1] * wobble, 0.0))
    faces = [(0, i + 1, (i + 1) % segments + 1) for i in range(segments)]
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    obj.location = center
    # Face the patch at the body before projecting it.
    obj.rotation_euler = (math.pi / 2, 0, 0)
    bpy.context.collection.objects.link(obj)
    activate(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    wrap = obj.modifiers.new("Wrap", "SHRINKWRAP")
    wrap.target = target
    wrap.wrap_method = "PROJECT"
    wrap.wrap_mode = "ABOVE_SURFACE"
    wrap.use_negative_direction = True
    wrap.use_positive_direction = True
    wrap.offset = offset
    bpy.ops.object.modifier_apply(modifier=wrap.name)
    solid = obj.modifiers.new("Skin", "SOLIDIFY")
    solid.thickness = 0.0018
    solid.offset = 0.0
    bpy.ops.object.modifier_apply(modifier=solid.name)
    obj.data.materials.append(material)
    return obj


def material_index_of(obj: bpy.types.Object, material) -> int:
    for i, slot in enumerate(obj.material_slots):
        if slot.material == material:
            return i
    obj.data.materials.append(material)
    return len(obj.data.materials) - 1


def paint_stain(obj: bpy.types.Object, center, radius: float, material, seed: int = 0) -> None:
    """Colour existing faces instead of adding geometry, so stains follow folds."""
    index = material_index_of(obj, material)
    c = Vector(center)
    for poly in obj.data.polygons:
        p = poly.center
        distance = (p - c).length
        edge = radius * (0.78 + 0.22 * math.sin(p.x * 150.0 + p.z * 95.0)
                          + 0.10 * math.sin(p.y * 230.0 + seed))
        if distance < edge:
            poly.material_index = index


def tear_hole(obj: bpy.types.Object, center, radius: float, flesh, seed: int = 0):
    """Cut a ragged hole and put torn flesh just under the cloth."""
    import bmesh
    c = Vector(center)
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    doomed = []
    for face in bm.faces:
        p = face.calc_center_median()
        if abs(p.x - c.x) > radius * 1.4 or abs(p.z - c.z) > radius * 1.4:
            continue
        edge = radius * (0.72 + 0.24 * math.sin(p.x * 130.0 + p.z * 170.0 + seed)
                         + 0.12 * math.cos(p.y * 210.0))
        if (p - c).length < edge:
            doomed.append(face)
    if doomed:
        bmesh.ops.delete(bm, geom=doomed, context="FACES")
    bm.to_mesh(obj.data)
    bm.free()
    obj.data.update()
    inner = sphere("TornFlesh", (c.x, c.y + 0.030, c.z),
                   (radius * 0.90, 0.012, radius * 0.90), 32, 24)
    inner.data.materials.append(flesh)
    return inner


def build_head_sculpt(male: bool, seed: int, crawl: bool = False) -> list[bpy.types.Object]:
    """One fused, carved head instead of parts resting against each other.

    The skull is a lofted anatomical profile. Every feature that should read as
    bone or flesh -- brow, cheekbones, nose, lips, chin, jaw, ears, eyelids -- is
    joined to it and voxel-fused, so the transitions are continuous. The sockets,
    mouth and nostrils are then cut out of that single skin.
    """
    rng = random.Random(seed)
    rings = [
        head_ring(1.492, 0.055, 0.046, 0.050),
        head_ring(1.520, 0.050, 0.056, 0.056),
        head_ring(1.545, 0.042, 0.066, 0.062),
        head_ring(1.568, 0.034, 0.076, 0.066),
        head_ring(1.585, 0.038, 0.080, 0.070),
        head_ring(1.602, 0.046, 0.083, 0.074),
        head_ring(1.620, 0.050, 0.085, 0.077),
        head_ring(1.638, 0.052, 0.086, 0.079),
        head_ring(1.658, 0.052, 0.082, 0.081),
        head_ring(1.678, 0.057, 0.082, 0.083),
        head_ring(1.700, 0.060, 0.080, 0.085),
        head_ring(1.720, 0.062, 0.079, 0.086),
        head_ring(1.742, 0.061, 0.074, 0.087),
        head_ring(1.762, 0.056, 0.066, 0.084),
        head_ring(1.780, 0.047, 0.054, 0.075),
        head_ring(1.794, 0.033, 0.038, 0.058),
        head_ring(1.802, 0.015, 0.020, 0.032),
    ]
    if crawl:
        # The crawler gets a separate neck bridge; keeping the vertical neck in the
        # skull leaves a white column hanging under the chin.
        rings = rings[4:]
    head = loft("HeadBase", rings, True, True)
    smooth(head, 1)
    apply_modifiers(head)

    features: list[bpy.types.Object] = [head]

    # Brow ridges and glabella.
    for side in (-1, 1):
        brow = tube(f"Brow{side}",
                    [(side * 0.010, -0.078, 1.724), (side * 0.032, -0.082, 1.728),
                     (side * 0.056, -0.072, 1.720)],
                    [(0.010, 0.009), (0.012, 0.010), (0.008, 0.007)], 16)
        features.append(brow)
    ridge = tube("Glabella", [(0, -0.080, 1.735), (0, -0.084, 1.706)],
                 [(0.008, 0.007), (0.009, 0.008)], 12)
    features.append(ridge)

    # Cheekbones and jaw.
    for side in (-1, 1):
        cheek = sphere(f"Cheek{side}", (side * 0.050, -0.070, 1.682),
                       (0.024, 0.022, 0.017), 48, 32)
        features.append(cheek)
        jaw = sphere(f"JawAngle{side}", (side * 0.047, -0.006, 1.607),
                     (0.018, 0.032, 0.028), 40, 28)
        features.append(jaw)
    chin = sphere("Chin", (0, -0.078, 1.578), (0.024, 0.020, 0.019), 40, 28)
    features.append(chin)

    # Nose: bridge, tip, and wings, all fused into the face.
    nose = tube("NoseBridge",
                [(0, -0.076, 1.730), (0, -0.086, 1.702), (0, -0.098, 1.678),
                 (0, -0.106, 1.662)],
                [(0.007, 0.006), (0.010, 0.009), (0.015, 0.013), (0.018, 0.014)], 24)
    features.append(nose)
    for side in (-1, 1):
        ala = sphere(f"Ala{side}", (side * 0.016, -0.100, 1.659),
                     (0.011, 0.013, 0.010), 32, 24)
        features.append(ala)

    # Lips as rolled bands. The mouth is cut between them later.
    upper_lip = tube("UpperLip",
                     [(-0.030, -0.084, 1.631), (-0.018, -0.090, 1.635),
                      (0.000, -0.092, 1.636), (0.018, -0.090, 1.635),
                      (0.030, -0.084, 1.631)],
                     [(0.005, 0.004), (0.007, 0.006), (0.008, 0.006),
                      (0.007, 0.006), (0.005, 0.004)], 12)
    features.append(upper_lip)
    lower_lip = tube("LowerLip",
                     [(-0.028, -0.083, 1.608), (-0.016, -0.089, 1.604),
                      (0.000, -0.091, 1.603), (0.016, -0.089, 1.604),
                      (0.028, -0.083, 1.608)],
                     [(0.005, 0.004), (0.008, 0.007), (0.009, 0.007),
                      (0.008, 0.007), (0.005, 0.004)], 12)
    features.append(lower_lip)

    # A heavy supraorbital shelf hoods the sockets; there are no separate lid sacks.
    for side in (-1, 1):
        hood = sphere(f"BrowHood{side}", (side * 0.031, -0.072, 1.713),
                      (0.022, 0.012, 0.007), 32, 24)
        features.append(hood)
    # Fill the jaw-to-neck transition so the chin does not read as a ball on a pole.
    if not crawl:
        features.append(sphere("JawNeck", (0, -0.026, 1.586), (0.036, 0.040, 0.030), 40, 28))
        features.append(sphere("NeckRoot", (0, 0.002, 1.545), (0.052, 0.052, 0.045), 40, 28))

    # Ears.
    for side in (-1, 1):
        ear = sphere(f"Ear{side}", (side * 0.063, 0.004, 1.688),
                     (0.007, 0.015, 0.026), 32, 24)
        lobe = sphere(f"EarLobe{side}", (side * 0.061, -0.002, 1.667),
                      (0.006, 0.010, 0.010), 24, 16)
        features.extend([ear, lobe])

    head = fuse(features, "Head", 0.0024, 2, 1)
    apply_modifiers(head)

    cutters = []
    for side in (-1, 1):
        cutters.append(sphere(f"SocketCut{side}", (side * 0.031, -0.086, 1.700),
                              (0.0220, 0.0230, 0.0170), 48, 32))
    cutters.append(sphere("MouthCut", (0, -0.094, 1.616), (0.034, 0.028, 0.0165), 48, 32))
    for side in (-1, 1):
        cutters.append(sphere(f"NostrilCut{side}", (side * 0.008, -0.110, 1.651),
                              (0.005, 0.008, 0.0045), 24, 16))
        cutters.append(sphere(f"EarCut{side}", (side * 0.069, 0.000, 1.688),
                              (0.0050, 0.0090, 0.0160), 24, 16))
    for cutter in cutters:
        activate(head)
        boolean = head.modifiers.new("Cut", "BOOLEAN")
        boolean.operation = "DIFFERENCE"
        boolean.solver = "EXACT"
        boolean.object = cutter
        bpy.ops.object.modifier_apply(modifier=boolean.name)
        bpy.data.objects.remove(cutter, do_unlink=True)
    activate(head)
    bpy.ops.object.shade_smooth()

    # Boolean cutters leave an empty leading slot behind; clear it after the cuts.
    head.data.materials.clear()
    skin = skin_material()
    head.data.materials.append(skin)
    lip_mat = new_material("MouthLips", (0.075, 0.028, 0.026), 0.44)
    head.data.materials.append(lip_mat)
    muscle = simple_material("ExposedMuscle", (0.085, 0.016, 0.012), 0.40)
    head.data.materials.append(muscle)

    # Skin folds: forehead lines, crow's feet, nasolabial creases, and a sunken
    # cheek. These are displaced into the fused surface, not drawn on top.
    mesh = head.data
    coords = np.empty(len(mesh.vertices) * 3, dtype=np.float32)
    mesh.vertices.foreach_get("co", coords)
    coords = coords.reshape(-1, 3)
    x, y, z = coords[:, 0], coords[:, 1], coords[:, 2]
    for side in (-1, 1):
        nasolabial = ((np.abs(x - side * 0.030) < 0.006) & (z > 1.620) & (z < 1.665)
                      & (y < -0.070))
        coords[nasolabial, 1] += 0.0016
        crows = ((np.abs(x - side * 0.046) < 0.010) & (z > 1.695) & (z < 1.718)
                 & (y < -0.060))
        coords[crows, 1] += 0.0009 * np.sin(z[crows] * 700.0)
    mesh.vertices.foreach_set("co", coords.ravel())
    mesh.update()

    # Paint the mouth rim and three lesions by material, so they follow the skin.
    for poly in mesh.polygons:
        center = poly.center
        if abs(center.x) < 0.036 and center.y < -0.070 and 1.598 < center.z < 1.638:
            poly.material_index = 1
    lesions = [
        ((0.050, -0.062, 1.748), 0.021),
        ((-0.055, -0.058, 1.690), 0.018),
        ((0.046, -0.060, 1.612), 0.015),
        ((-0.020, 0.058, 1.738), 0.022),
    ]
    for center, radius in lesions:
        c = Vector(center)
        for poly in mesh.polygons:
            p = poly.center
            if (p - c).length < radius and (p - c).normalized().dot(Vector((0, -1, 0))) > -0.15:
                poly.material_index = 2

    parts: list[bpy.types.Object] = [head]

    dark = simple_material("Socket", (0.009, 0.004, 0.003), 0.42)
    skull_inner = sphere("SkullInner", (0, 0.004, 1.700), (0.055, 0.058, 0.076), 40, 28)
    skull_inner.data.materials.append(dark)
    parts.append(skull_inner)
    sclera_mat = new_material("EyeSclera", (0.31, 0.27, 0.19), 0.18, sss=0.06)
    iris_mat = new_material("EyeIris", (0.11, 0.050, 0.026), 0.28)
    pupil_mat = new_material("Pupil", (0.003, 0.002, 0.002), 0.20)
    for side in (-1, 1):
        eye = sphere(f"Eye{side}", (side * 0.031, -0.0745, 1.700),
                     (0.0088, 0.0088, 0.0088), 64, 48)
        eye.data.materials.append(sclera_mat)
        parts.append(eye)
        iris = sphere(f"Iris{side}", (side * 0.031, -0.0830, 1.6995),
                      (0.0046, 0.0016, 0.0046), 48, 32)
        iris.data.materials.append(iris_mat)
        parts.append(iris)
        pupil = sphere(f"Pupil{side}", (side * 0.031, -0.0920, 1.6995),
                       (0.0024, 0.0012, 0.0024), 32, 24)
        pupil.data.materials.append(pupil_mat)
        parts.append(pupil)
    tongue = rough_ellipsoid("Tongue", (0, -0.055, 1.608), (0.023, 0.021, 0.009), 0.22, seed + 4)
    tongue.data.materials.append(new_material("TongueMat", (0.19, 0.052, 0.052), 0.36, sss=0.2))
    parts.append(tongue)
    gums_mat = new_material("Gums", (0.14, 0.024, 0.022), 0.42)
    teeth_mat = new_material("Teeth", (0.28, 0.22, 0.12), 0.40)
    for row in (-1, 1):
        gum = tube(f"Gums{row}",
                   [(-0.030, -0.064, 1.617 + row * 0.0105),
                    (-0.015, -0.069, 1.618 + row * 0.0115),
                    (0.000, -0.071, 1.618 + row * 0.0115),
                    (0.015, -0.069, 1.618 + row * 0.0115),
                    (0.030, -0.064, 1.617 + row * 0.0105)],
                   [(0.006, 0.005), (0.008, 0.006), (0.008, 0.006),
                    (0.008, 0.006), (0.006, 0.005)], 12)
        gum.data.materials.append(gums_mat)
        parts.append(gum)
        for i in range(9):
            tx = -0.030 + i * 0.0076 + (rng.random() - 0.5) * 0.0016
            length = 0.008 + rng.random() * 0.005
            if i in (2, 7):
                length *= 1.30
            tilt = (rng.random() - 0.5) * 0.55
            tooth = rounded_box(f"Tooth{row}_{i}",
                                (tx, -0.0760 + abs(tx) * 0.08, 1.617 + row * (0.0035 + length * 0.5)),
                                (0.0031, 0.0026, length), 0.0011, 1)
            tooth.rotation_euler = (tilt * 0.4, tilt, tilt * 0.5)
            tooth.data.materials.append(teeth_mat)
            parts.append(tooth)

    if not male and not crawl:
        parts.extend(build_hair(seed))

    # Blood runs down from the mouth and the damaged brow. Thin tubes sit in the
    # skin creases instead of hovering like decals.
    blood = blood_material()
    for i, (start, end) in enumerate([
        ((-0.022, -0.089, 1.606), (-0.026, -0.083, 1.566)),
        ((0.018, -0.090, 1.604), (0.022, -0.086, 1.560)),
        ((-0.052, -0.072, 1.712), (-0.056, -0.068, 1.690)),
    ]):
        drip = tube(f"FaceDrip{i}", [start, ((start[0] + end[0]) / 2,
                                            (start[1] + end[1]) / 2 - 0.002,
                                            (start[2] + end[2]) / 2), end],
                    [(0.0014, 0.0010), (0.0010, 0.0008), (0.0004, 0.0003)], 8)
        drip.data.materials.append(blood)
        parts.append(drip)
    return parts


def build_head_meta(male: bool, seed: int) -> list[bpy.types.Object]:
    """A head modelled as one continuous implicit surface.

    Ellipsoids and carved negative volumes are fused by the metaball field, so the
    brow, cheekbones, jaw and nose are part of the same skin rather than separate
    primitives resting on a sphere. The sockets and mouth are then cut as real
    openings, which is what lets the eyes sit in shadow and the teeth read.
    """
    rng = random.Random(seed)
    mb = bpy.data.metaballs.new("HeadField")
    mb.resolution = 0.0026
    mb.render_resolution = 0.0026
    mb.threshold = 0.55
    head = bpy.data.objects.new("Head", mb)
    bpy.context.collection.objects.link(head)

    positives = [
        # Cranium and forehead.
        ((0.000, 0.014, 1.742), (0.068, 0.080, 0.074)),
        ((0.000, 0.040, 1.704), (0.058, 0.055, 0.060)),
        ((0.000, -0.026, 1.750), (0.060, 0.048, 0.048)),
        # Brow, glabella, midface.
        ((0.030, -0.075, 1.727), (0.032, 0.024, 0.015)),
        ((-0.030, -0.075, 1.727), (0.032, 0.024, 0.015)),
        ((0.000, -0.069, 1.731), (0.017, 0.018, 0.014)),
        ((0.000, -0.028, 1.690), (0.055, 0.052, 0.040)),
        ((0.048, -0.048, 1.690), (0.024, 0.026, 0.019)),
        ((-0.048, -0.048, 1.690), (0.024, 0.026, 0.019)),
        # Upper jaw, mandible, chin.
        ((0.000, -0.064, 1.648), (0.044, 0.040, 0.030)),
        ((0.030, -0.050, 1.640), (0.024, 0.034, 0.026)),
        ((-0.030, -0.050, 1.640), (0.024, 0.034, 0.026)),
        ((0.000, -0.058, 1.601), (0.043, 0.041, 0.030)),
        ((0.050, 0.000, 1.610), (0.018, 0.036, 0.028)),
        ((-0.050, 0.000, 1.610), (0.018, 0.036, 0.028)),
        ((0.000, -0.077, 1.584), (0.024, 0.019, 0.018)),
        ((0.036, -0.058, 1.596), (0.014, 0.018, 0.016)),
        ((-0.036, -0.058, 1.596), (0.014, 0.018, 0.016)),
        # Nose.
        ((0.000, -0.090, 1.714), (0.010, 0.020, 0.028)),
        ((0.000, -0.110, 1.668), (0.015, 0.019, 0.014)),
        ((0.016, -0.104, 1.661), (0.011, 0.014, 0.010)),
        ((-0.016, -0.104, 1.661), (0.011, 0.014, 0.010)),
        # Philtrum and lips.
        ((0.000, -0.092, 1.640), (0.006, 0.008, 0.008)),
        ((0.000, -0.096, 1.627), (0.022, 0.010, 0.006)),
        ((0.000, -0.095, 1.610), (0.020, 0.010, 0.007)),
        # Eyelid rims: they blend into the brow and cheek before the socket is cut.
        ((0.032, -0.086, 1.713), (0.019, 0.008, 0.006)),
        ((-0.032, -0.086, 1.713), (0.019, 0.008, 0.006)),
        ((0.032, -0.086, 1.686), (0.017, 0.007, 0.005)),
        ((-0.032, -0.086, 1.686), (0.017, 0.007, 0.005)),
        # Neck.
        ((0.000, 0.010, 1.548), (0.048, 0.046, 0.052)),
        ((0.000, -0.022, 1.566), (0.034, 0.030, 0.040)),
        # Ears.
        ((0.076, 0.004, 1.692), (0.008, 0.016, 0.028)),
        ((-0.076, 0.004, 1.692), (0.008, 0.016, 0.028)),
        ((0.074, -0.002, 1.666), (0.007, 0.010, 0.010)),
        ((-0.074, -0.002, 1.666), (0.007, 0.010, 0.010)),
    ]
    for co, size in positives:
        meta_element(mb, co, size)

    negatives = [
        ((0.062, -0.044, 1.735), (0.012, 0.020, 0.024)),
        ((-0.062, -0.044, 1.735), (0.012, 0.020, 0.024)),
        ((0.045, -0.064, 1.657), (0.012, 0.014, 0.018)),
        ((-0.045, -0.064, 1.657), (0.012, 0.014, 0.018)),
        ((0.000, -0.103, 1.654), (0.009, 0.010, 0.005)),
        ((0.000, -0.099, 1.618), (0.024, 0.012, 0.0045)),
        ((0.000, -0.099, 1.600), (0.018, 0.008, 0.004)),
        ((0.080, -0.001, 1.688), (0.005, 0.008, 0.012)),
        ((-0.080, -0.001, 1.688), (0.005, 0.008, 0.012)),
    ]
    for co, size in negatives:
        meta_element(mb, co, size, stiffness=3.0, negative=True)

    activate(head)
    bpy.ops.object.convert(target="MESH")
    head = bpy.context.object
    head.name = "Head"
    smooth_mod = head.modifiers.new("Relax", "SMOOTH")
    smooth_mod.factor = 0.5
    smooth_mod.iterations = 2
    bpy.ops.object.modifier_apply(modifier=smooth_mod.name)
    bpy.ops.object.shade_smooth()

    # Cut the sockets and mouth as genuine openings.
    cutters = []
    for side in (-1, 1):
        cutter = sphere(f"SocketCut{side}", (side * 0.032, -0.086, 1.700),
                        (0.0215, 0.021, 0.0165), 48, 32)
        cutters.append(cutter)
    mouth_cut = sphere("MouthCut", (0, -0.092, 1.617), (0.030, 0.026, 0.0135), 48, 32)
    cutters.append(mouth_cut)
    for side in (-1, 1):
        nostril_cut = sphere(f"NostrilCut{side}", (side * 0.008, -0.113, 1.652),
                             (0.0055, 0.008, 0.0045), 24, 16)
        cutters.append(nostril_cut)
    for cutter in cutters:
        activate(head)
        boolean = head.modifiers.new("Cut", "BOOLEAN")
        boolean.operation = "DIFFERENCE"
        boolean.solver = "EXACT"
        boolean.object = cutter
        bpy.ops.object.modifier_apply(modifier=boolean.name)
        bpy.data.objects.remove(cutter, do_unlink=True)
    activate(head)
    bpy.ops.object.shade_smooth()

    skin = skin_material()
    # Boolean cutters bring their own empty material slot; clear it or the whole
    # head renders in Blender's default white clay.
    head.data.materials.clear()
    head.data.materials.append(skin)
    lip_mat = new_material("MouthLips", (0.085, 0.032, 0.030), 0.44)
    head.data.materials.append(lip_mat)
    # Tint the cut rim around the mouth without a separate sausage-like lip.
    for poly in head.data.polygons:
        center = poly.center
        if (abs(center.x) < 0.045 and center.y < -0.075
                and 1.598 < center.z < 1.633):
            poly.material_index = 1

    # Fine skin relief and sagging: low-frequency folds over the skull.
    displace_noise(head, 0.020, 0.0026, "SkinFolds", 3)
    displace_noise(head, 0.0045, 0.0007, "Pores", 1)
    activate(head)
    bpy.ops.object.modifier_apply(modifier="SkinFolds")
    bpy.ops.object.modifier_apply(modifier="Pores")

    parts: list[bpy.types.Object] = [head]

    dark = simple_material("Socket", (0.012, 0.006, 0.005), 0.38)
    # Fill the skull behind the openings: the sockets and mouth then read as depth
    # instead of holes through to the background.
    skull_inner = sphere("SkullInner", (0, 0.005, 1.700), (0.060, 0.060, 0.080), 48, 32)
    skull_inner.data.materials.append(dark)
    parts.append(skull_inner)
    sclera_mat = new_material("EyeSclera", (0.52, 0.46, 0.33), 0.16, sss=0.08)
    iris_mat = new_material("EyeIris", (0.12, 0.055, 0.028), 0.28)
    pupil_mat = new_material("Pupil", (0.003, 0.002, 0.002), 0.20)
    for side in (-1, 1):
        eye = sphere(f"Eye{side}", (side * 0.032, -0.0790, 1.700),
                     (0.0112, 0.0112, 0.0112), 64, 48)
        eye.data.materials.append(sclera_mat)
        parts.append(eye)
        iris = sphere(f"Iris{side}", (side * 0.032, -0.0890, 1.6995),
                      (0.0060, 0.0020, 0.0060), 48, 32)
        iris.data.materials.append(iris_mat)
        parts.append(iris)
        pupil = sphere(f"Pupil{side}", (side * 0.032, -0.0900, 1.6995),
                       (0.0028, 0.0014, 0.0028), 32, 24)
        pupil.data.materials.append(pupil_mat)
        parts.append(pupil)
        # A rolled lid around the socket rim hides the clean boolean edge.
        bpy.ops.mesh.primitive_torus_add(
            major_radius=0.0155, minor_radius=0.0028,
            location=(side * 0.032, -0.0885, 1.700),
            rotation=(math.pi / 2, 0, 0),
        )
        lid = bpy.context.object
        lid.name = f"Eyelid{side}"
        lid.scale = (1.0, 1.0, 0.86)
        smooth(lid, 1)
        lid.data.materials.append(skin_material())
        parts.append(lid)
        # Broken capillaries across the sclera.
        for i in range(3):
            vein = tube(f"EyeVein{side}_{i}",
                        [(side * 0.032 - 0.010 + i * 0.004, -0.0893, 1.704 - i * 0.003),
                         (side * 0.032 - 0.002, -0.0897, 1.701 - i * 0.002),
                         (side * 0.032 + 0.009, -0.0893, 1.699 + i * 0.002)],
                        [(0.0006, 0.0005), (0.0008, 0.0006), (0.0005, 0.0004)], 6)
            vein.data.materials.append(simple_material(f"EyeVeinMat{side}_{i}", (0.22, 0.045, 0.030), 0.3))
            parts.append(vein)

    # Mouth interior, gums, tongue and irregular teeth.
    tongue = rough_ellipsoid("Tongue", (0, -0.058, 1.607), (0.024, 0.022, 0.009), 0.22, seed + 4)
    tongue.data.materials.append(new_material("TongueMat", (0.20, 0.055, 0.055), 0.36, sss=0.2))
    parts.append(tongue)
    gums_mat = new_material("Gums", (0.14, 0.024, 0.022), 0.42)
    teeth_mat = new_material("Teeth", (0.42, 0.34, 0.20), 0.34)
    for row in (-1, 1):
        gum = tube(f"Gums{row}",
                   [(-0.029, -0.068, 1.617 + row * 0.0105),
                    (-0.015, -0.072, 1.618 + row * 0.0115),
                    (0.000, -0.074, 1.618 + row * 0.0115),
                    (0.015, -0.072, 1.618 + row * 0.0115),
                    (0.029, -0.068, 1.617 + row * 0.0105)],
                   [(0.006, 0.005), (0.008, 0.006), (0.008, 0.006),
                    (0.008, 0.006), (0.006, 0.005)], 12)
        gum.data.materials.append(gums_mat)
        parts.append(gum)
        for i in range(9):
            tx = -0.028 + i * 0.007
            length = 0.010 + rng.random() * 0.004
            if i in (2, 7):
                length *= 1.25
            tilt = (rng.random() - 0.5) * 0.35
            tooth = rounded_box(f"Tooth{row}_{i}",
                                (tx, -0.0755 + abs(tx) * 0.10, 1.617 + row * (0.0035 + length * 0.5)),
                                (0.0032, 0.0028, length), 0.0012, 1)
            tooth.rotation_euler = (tilt * 0.4, tilt, tilt * 0.5)
            tooth.data.materials.append(teeth_mat)
            parts.append(tooth)

    # Hair or stubble.
    if not male:
        parts.extend(build_hair(seed))
    else:
        for i in range(10):
            angle = rng.random() * TAU
            patch = surface_patch(f"ScalpPatch{i}", head,
                                  (0.055 * math.cos(angle), 0.02 + 0.055 * math.sin(angle), 1.792),
                                  0.014, simple_material(f"Stubble{i}", (0.028, 0.024, 0.020), 0.8), seed + i)
            parts.append(patch)

    # Necrotic damage: dark exposed muscle, wet blood, and a torn cheek.
    muscle = simple_material("ExposedMuscle", (0.095, 0.018, 0.014), 0.42)
    blood = blood_material()
    wounds = [
        ((0.054, -0.080, 1.752), 0.024, muscle, 0.8),
        ((-0.062, -0.072, 1.706), 0.020, muscle, 1.6),
        ((0.030, -0.086, 1.586), 0.015, blood, 0.6),
        ((0.070, 0.030, 1.740), 0.018, blood, 2.2),
        ((-0.028, 0.070, 1.703), 0.022, muscle, 0.4),
    ]
    for i, (center, radius, material, squash) in enumerate(wounds):
        patch = surface_patch(f"FaceWound{i}", head, center, radius, material, seed + i,
                              squash=(1.0, squash))
        parts.append(patch)
    # Blood running from the mouth and the damaged eye.
    for i in range(3):
        drip = tube(f"FaceDrip{i}",
                    [(-0.024 + i * 0.024, -0.096, 1.600),
                     (-0.026 + i * 0.024, -0.098, 1.585),
                     (-0.024 + i * 0.024, -0.094, 1.566)],
                    [(0.0028, 0.0020), (0.0022, 0.0016), (0.0009, 0.0008)], 8)
        drip.data.materials.append(blood)
        parts.append(drip)
    return parts


def build_head_v2(male: bool, seed: int) -> list[bpy.types.Object]:
    """A skull built from anatomical masses and carved, not a sphere with stickers."""
    rng = random.Random(seed)
    skin = skin_material()
    parts: list[bpy.types.Object] = []

    cranium = sphere("Cranium", (0, 0.008, 1.703), (0.086, 0.097, 0.101), 96, 64)
    forehead = sphere("Forehead", (0, -0.035, 1.715), (0.070, 0.055, 0.060), 64, 48)
    jaw = sphere("Jaw", (0, -0.012, 1.605), (0.073, 0.085, 0.061), 64, 48)
    chin = sphere("Chin", (0, -0.058, 1.566), (0.040, 0.034, 0.030), 48, 32)
    cheek_l = sphere("CheekL", (-0.052, -0.055, 1.646), (0.036, 0.031, 0.028), 48, 32)
    cheek_r = sphere("CheekR", (0.052, -0.055, 1.646), (0.036, 0.031, 0.028), 48, 32)
    brow_l = sphere("BrowL", (-0.034, -0.083, 1.703), (0.036, 0.021, 0.017), 40, 28)
    brow_r = sphere("BrowR", (0.034, -0.083, 1.703), (0.036, 0.021, 0.017), 40, 28)
    neck = tube("Neck", [(0, 0.0, 1.49), (0, -0.002, 1.56), (0, 0.0, 1.60)],
                [(0.056, 0.052), (0.052, 0.049), (0.050, 0.047)], 32)
    head = fuse([cranium, forehead, jaw, chin, cheek_l, cheek_r, brow_l, brow_r, neck],
                "Head", 0.0035, 4, 2)

    # Carve eye sockets into the fused skull.
    socket_centres = [Vector((-0.039, -0.094, 1.686)), Vector((0.039, -0.094, 1.686))]
    for vertex in head.data.vertices:
        for centre in socket_centres:
            distance = (vertex.co - centre).length
            if distance < 0.033:
                fall = (1.0 - distance / 0.033) ** 1.6
                vertex.co += (centre - vertex.co).normalized() * 0.020 * fall
        # Mouth slit.
        if abs(vertex.co.x) < 0.050 and vertex.co.y < -0.070 and 1.594 < vertex.co.z < 1.630:
            fall = (1.0 - abs(vertex.co.z - 1.612) / 0.018)
            vertex.co.y += 0.014 * fall
    head.data.update()
    head.data.materials.append(skin)
    parts.append(head)

    socket_mat = simple_material("Socket", (0.012, 0.006, 0.005), 0.35)
    eye_white = new_material("EyeSclera", (0.50, 0.44, 0.29), 0.16, sss=0.12)
    iris_mat = new_material("EyeIris", (0.15, 0.055, 0.022), 0.28)
    pupil_mat = new_material("Pupil", (0.003, 0.002, 0.002), 0.20)
    for side in (-1, 1):
        cavity = sphere(f"SocketCavity{side}", (side * 0.039, -0.086, 1.686),
                        (0.026, 0.024, 0.023), 48, 32)
        cavity.data.materials.append(socket_mat)
        parts.append(cavity)
        eye = sphere(f"Eye{side}", (side * 0.039, -0.100, 1.686),
                     (0.013, 0.013, 0.013), 64, 48)
        eye.data.materials.append(eye_white)
        parts.append(eye)
        iris = sphere(f"Iris{side}", (side * 0.039, -0.1115, 1.686),
                      (0.0072, 0.002, 0.0072), 48, 32)
        iris.data.materials.append(iris_mat)
        parts.append(iris)
        pupil = sphere(f"Pupil{side}", (side * 0.039, -0.1132, 1.686),
                       (0.0029, 0.0014, 0.0029), 32, 24)
        pupil.data.materials.append(pupil_mat)
        parts.append(pupil)
        # Bony orbital rim.
        ring = bpy.data.curves.new(f"Orbit{side}", type="CURVE")
        ring.dimensions = "3D"
        ring.bevel_depth = 0.0038
        ring.bevel_resolution = 2
        spline = ring.splines.new("POLY")
        points = []
        for i in range(18):
            a = TAU * i / 18
            points.append((side * 0.039 + 0.024 * math.cos(a),
                           -0.092 + 0.006 * math.sin(a),
                           1.686 + 0.018 * math.sin(a)))
        spline.points.add(len(points) - 1)
        for p, point in zip(spline.points, points):
            p.co = (*point, 1.0)
        rim = bpy.data.objects.new(f"Orbit{side}", ring)
        bpy.context.collection.objects.link(rim)
        activate(rim)
        bpy.ops.object.convert(target="MESH")
        rim = bpy.context.object
        rim.data.materials.append(skin)
        parts.append(rim)

    # Nose, lips, teeth, gums, ears.
    nose = tube("Nose", [(0, -0.085, 1.715), (0, -0.108, 1.676), (0, -0.114, 1.654)],
                [(0.012, 0.010), (0.020, 0.016), (0.022, 0.015)], 20)
    nose.data.materials.append(skin)
    parts.append(nose)
    for side in (-1, 1):
        nostril = sphere(f"Nostril{side}", (side * 0.011, -0.1105, 1.650),
                         (0.0055, 0.004, 0.0045), 20, 16)
        nostril.data.materials.append(socket_mat)
        parts.append(nostril)

    cavity = rounded_box("MouthCavity", (0, -0.062, 1.612), (0.056, 0.038, 0.027), 0.02, 1)
    cavity.data.materials.append(socket_mat)
    parts.append(cavity)
    teeth_mat = new_material("Teeth", (0.43, 0.36, 0.21), 0.32)
    gums_mat = new_material("Gums", (0.17, 0.030, 0.026), 0.42)
    lip_mat = new_material("Lips", (0.105, 0.042, 0.038), 0.45)
    for row in (-1, 1):
        gum = rounded_box(f"Gums{row}", (0, -0.093, 1.612 + row * 0.020),
                          (0.050, 0.012, 0.009), 0.005, 1)
        gum.data.materials.append(gums_mat)
        parts.append(gum)
        for i in range(8):
            tx = -0.041 + i * 0.0118
            tooth = rounded_box(f"Tooth{row}_{i}", (tx, -0.095, 1.612 + row * 0.022),
                                (0.0063, 0.0045, 0.0095 + rng.random() * 0.0035), 0.002, 1)
            tooth.data.materials.append(teeth_mat)
            parts.append(tooth)
    for row, z in ((-1, 1.634), (1, 1.594)):
        lip = tube(f"Lip{row}",
                   [(-0.044, -0.100, z), (-0.022, -0.108, z + row * 0.002),
                    (0.0, -0.110, z), (0.022, -0.108, z + row * 0.002),
                    (0.044, -0.100, z)],
                   [(0.004, 0.0035), (0.0065, 0.0055), (0.0075, 0.006),
                    (0.0065, 0.0055), (0.004, 0.0035)], 12)
        lip.data.materials.append(lip_mat)
        parts.append(lip)

    ear_mat = new_material("EarSkin", (0.18, 0.15, 0.11), 0.55, sss=0.15)
    for side in (-1, 1):
        ear = sphere(f"Ear{side}", (side * 0.088, 0.0, 1.670),
                     (0.010, 0.020, 0.032), 48, 32)
        ear.data.materials.append(ear_mat)
        parts.append(ear)

    if not male:
        parts.extend(build_hair(seed))
    else:
        # Shaved scalp with a few dark patches, not a full second head.
        for i in range(5):
            angle = rng.random() * TAU
            patch = rough_ellipsoid(f"ScalpPatch{i}",
                                    (0.055 * math.cos(angle), 0.015 + 0.055 * math.sin(angle), 1.775),
                                    (0.022, 0.018, 0.004), 0.35, seed + i)
            patch.data.materials.append(simple_material(f"Stubble{i}", (0.035, 0.030, 0.024), 0.85))
            parts.append(patch)

    blood = blood_material()
    for i, (center, scale) in enumerate([
        ((0.052, -0.088, 1.735), (0.024, 0.006, 0.018)),
        ((-0.060, -0.082, 1.606), (0.018, 0.005, 0.015)),
        ((0.016, -0.078, 1.558), (0.015, 0.004, 0.018)),
        ((-0.070, -0.070, 1.672), (0.012, 0.004, 0.024)),
    ]):
        wound = rough_ellipsoid(f"FaceWound{i}", center, scale, 0.20, seed + i)
        wound.data.materials.append(blood)
        parts.append(wound)
    return parts


def build_hair(seed: int) -> list[bpy.types.Object]:
    rng = random.Random(seed)
    strands = []
    hair_mat = new_material("Hair", (0.035, 0.022, 0.014), 0.42)
    cap = sphere("HairCap", (0, 0.025, 1.742), (0.088, 0.096, 0.064), 64, 48)
    cap.data.materials.append(hair_mat)
    parts = [cap]
    for i in range(260):
        # Hair grows from the back and sides of the scalp. Strands seeded on the
        # forehead hang over the face and read as a curtain, so keep them behind
        # the ears and let a few side locks frame the jaw.
        angle = math.pi * (0.10 + rng.random() * 0.80)
        if i % 9 == 0:
            angle = math.pi * (-0.10 + rng.random() * 0.20)
        radius = 0.088 + rng.random() * 0.012
        x = math.cos(angle) * radius
        y = math.sin(angle) * radius * 0.75 + 0.01
        z = 1.74 + (rng.random() - 0.5) * 0.06
        points = []
        dx = x * 0.4
        dy = y * 0.3
        length = 0.24 + rng.random() * 0.24
        for step in range(7):
            t = step / 6.0
            points.append((
                x + dx * t + math.sin(t * 5 + i) * 0.012 * t,
                y + dy * t + math.cos(t * 4 + i) * 0.010 * t,
                z - length * t,
            ))
        curve = bpy.data.curves.new(f"HairStrand{i}", type="CURVE")
        curve.dimensions = "3D"
        curve.resolution_u = 4
        curve.bevel_depth = 0.0022 * (0.6 + rng.random() * 0.8)
        curve.bevel_resolution = 2
        spline = curve.splines.new("POLY")
        spline.points.add(len(points) - 1)
        for p, point in zip(spline.points, points):
            p.co = (*point, 1.0)
        obj = bpy.data.objects.new(f"HairStrand{i}", curve)
        bpy.context.collection.objects.link(obj)
        strands.append(obj)
    activate(strands[0])
    for strand in strands:
        strand.select_set(True)
    bpy.ops.object.convert(target="MESH")
    hair = join([o for o in bpy.context.selected_objects], "Hair")
    hair.data.materials.append(hair_mat)
    return parts + [hair]


def build_hand(side: int, wrist, scale: float = 1.0, curl: float = 1.0) -> list[bpy.types.Object]:
    """A palm with five tapered fingers, built around a wrist point."""
    parts = []
    skin = skin_material()
    palm = rounded_box(f"Palm{side}",
                       (wrist[0] + side * 0.006, wrist[1] - 0.012, wrist[2] - 0.032),
                       (0.058 * scale, 0.036 * scale, 0.066 * scale), 0.014, 2)
    palm.data.materials.append(skin)
    parts.append(palm)
    base_z = wrist[2] - 0.055 * scale
    for finger in range(4):
        fx = wrist[0] + side * (-0.033 + finger * 0.022) * scale
        length = (0.064 - abs(finger - 1.4) * 0.007) * scale
        points = [
            (fx, wrist[1] - 0.018, base_z),
            (fx + side * 0.003, wrist[1] - 0.030 - 0.012 * curl, base_z - length * 0.45),
            (fx + side * 0.005, wrist[1] - 0.040 - 0.020 * curl, base_z - length * 0.78),
            (fx + side * 0.006, wrist[1] - 0.036 - 0.030 * curl, base_z - length),
        ]
        finger_obj = tube(f"Finger{side}_{finger}", points,
                          [(0.0125 * scale, 0.0125 * scale),
                           (0.0112 * scale, 0.0112 * scale),
                           (0.0092 * scale, 0.0092 * scale),
                           (0.0050 * scale, 0.0050 * scale)], 14)
        finger_obj.data.materials.append(skin)
        parts.append(finger_obj)
    # Thumb: off the side of the palm, angled across.
    thumb_points = [
        (wrist[0] + side * 0.045 * scale, wrist[1] - 0.016, wrist[2] - 0.040),
        (wrist[0] + side * 0.062 * scale, wrist[1] - 0.040, wrist[2] - 0.055),
        (wrist[0] + side * 0.060 * scale, wrist[1] - 0.060, wrist[2] - 0.074),
    ]
    thumb = tube(f"Thumb{side}", thumb_points,
                 [(0.013 * scale, 0.013 * scale), (0.011 * scale, 0.011 * scale),
                  (0.006 * scale, 0.006 * scale)], 14)
    thumb.data.materials.append(skin)
    parts.append(thumb)
    # Knuckle plates: small bumps that read in silhouette.
    for finger in range(4):
        fx = wrist[0] + side * (-0.033 + finger * 0.022) * scale
        knuckle = sphere(f"Knuckle{side}_{finger}",
                         (fx, wrist[1] - 0.025, base_z + 0.008 * scale),
                         (0.011 * scale, 0.011 * scale, 0.010 * scale), 24, 16)
        knuckle.data.materials.append(skin)
        parts.append(knuckle)
    return parts


def build_boot(name: str, x: float, y: float) -> list[bpy.types.Object]:
    parts = []
    leather = simple_material(f"{name}Leather", (0.085, 0.060, 0.040), 0.48)
    sole = simple_material(f"{name}Sole", (0.018, 0.016, 0.014), 0.72)
    # Ankle and shaft.
    shaft = tube(f"{name}Shaft",
                 [(x, y + 0.02, 0.32), (x, y + 0.01, 0.18), (x, y, 0.09)],
                 [(0.062, 0.062), (0.066, 0.068), (0.070, 0.080)], 28)
    shaft.data.materials.append(leather)
    parts.append(shaft)
    # Foot: rings along Y, from heel to toe.
    verts, faces = [], []
    sections = [
        (0.09, 0.04, 0.050, 0.045),
        (0.02, 0.03, 0.062, 0.055),
        (-0.06, 0.025, 0.066, 0.060),
        (-0.13, 0.02, 0.058, 0.050),
        (-0.18, 0.015, 0.038, 0.032),
    ]
    seg = 24
    for cy, cz, rx, rz in sections:
        for i in range(seg):
            a = TAU * i / seg
            verts.append((x + rx * math.cos(a), y + cy, cz + rz * math.sin(a)))
    for s in range(len(sections) - 1):
        for i in range(seg):
            j = (i + 1) % seg
            faces.append((s * seg + i, s * seg + j, (s + 1) * seg + j, (s + 1) * seg + i))
    faces.append(tuple(range(seg - 1, -1, -1)))
    faces.append(tuple((len(sections) - 1) * seg + i for i in range(seg)))
    foot = mesh_object(f"{name}Foot", verts, faces)
    foot.data.materials.append(leather)
    parts.append(foot)
    # Sole slab.
    sole_obj = rounded_box(f"{name}Sole", (x, y - 0.045, 0.014),
                           (0.075, 0.155, 0.020), 0.012, 1)
    sole_obj.data.materials.append(sole)
    parts.append(sole_obj)
    # Laces.
    lace = simple_material(f"{name}Lace", (0.16, 0.13, 0.09), 0.8)
    for i in range(5):
        lace_obj = tube(f"{name}Lace{i}",
                        [(x - 0.045, y - 0.035 - i * 0.022, 0.10 + i * 0.004),
                         (x + 0.045, y - 0.043 - i * 0.022, 0.10 + i * 0.004)],
                        [(0.0035, 0.0035), (0.0035, 0.0035)], 8)
        lace_obj.data.materials.append(lace)
        parts.append(lace_obj)
    return parts


def build_body(male: bool, pose: str, seed: int) -> list[bpy.types.Object]:
    rng = random.Random(seed)
    skin = skin_material()
    jacket = cloth_material("Jacket", (0.135, 0.155, 0.085), 0.55)
    trousers = cloth_material("Trousers", (0.12, 0.145, 0.080), 0.75)
    blood = blood_material()
    parts = []
    upper_parts: list[bpy.types.Object] = []
    lower_parts: list[bpy.types.Object] = []

    if pose == "stand":
        torso_rings = [
            ring_xy(0, 0.00, 0.88, 0.155, 0.115, 64),
            ring_xy(0, 0.00, 0.98, 0.160, 0.115, 64),
            ring_xy(0, 0.00, 1.08, 0.170, 0.120, 64),
            ring_xy(0, 0.00, 1.20, 0.180, 0.128, 64),
            ring_xy(0, 0.00, 1.32, 0.198, 0.140, 64),
            ring_xy(0, 0.00, 1.42, 0.215, 0.150, 64),
            ring_xy(0, 0.00, 1.49, 0.190, 0.135, 64),
            ring_xy(0, 0.00, 1.52, 0.150, 0.115, 64),
        ]
        torso = loft("JacketTorso", torso_rings)
        smooth(torso, 2)
        displace_noise(torso, 0.28, 0.010)
        displace_noise(torso, 0.09, 0.003, "Clouds2", 1)
        torso.data.materials.append(jacket)
        parts.append(torso)
        # Trouser seat: without it the two thigh tubes read as separate sausages.
        seat = loft("TrouserSeat", [
            ring_xy(0, 0.005, 0.93, 0.158, 0.112, 48),
            ring_xy(0, 0.005, 0.86, 0.166, 0.118, 48),
            ring_xy(0, 0.005, 0.80, 0.152, 0.110, 48),
        ])
        smooth(seat, 2)
        seat.data.materials.append(trousers)
        parts.append(seat)
        shoulder_z = 1.45
        hip_z = 0.91
        knee_z = 0.52
        ankle_z = 0.10
    else:
        # Crawl: the torso is nearly horizontal, hips high, chest low.
        spine = [
            (0, 0.18, 0.62), (0, 0.06, 0.68), (0, -0.10, 0.78),
            (0, -0.26, 0.86), (0, -0.40, 0.90), (0, -0.50, 0.91),
        ]
        torso = tube("JacketTorso", spine,
                     [(0.145, 0.105), (0.160, 0.115), (0.175, 0.120),
                      (0.190, 0.130), (0.195, 0.135), (0.160, 0.110)], 64)
        smooth(torso, 2)
        displace_noise(torso, 0.28, 0.010)
        torso.data.materials.append(jacket)
        parts.append(torso)
        shoulder_z = None

    # Neck (the standing head is upright; the crawler's head is raised).
    if pose == "stand":
        parts.extend(build_head(male, seed))
    else:
        head_parts = build_head(male, seed)
        # Move the whole head block forward and down, then tip it up to look ahead.
        pivot = Vector((0, -0.005, 1.62))
        target = Vector((0, -0.56, 0.98))
        angle = math.radians(-58)
        rot = Matrix.Rotation(angle, 4, "X")
        move = Matrix.Translation(target) @ rot @ Matrix.Translation(-pivot)
        for obj in head_parts:
            obj.matrix_world = move @ obj.matrix_world
        parts.extend(head_parts)

    # Arms.
    arm_objects = []
    for side in (-1, 1):
        if pose == "stand":
            shoulder = (side * 0.215, 0.00, 1.44)
            elbow = (side * 0.275, 0.01, 1.19)
            wrist = (side * 0.305, 0.04, 0.93)
        else:
            shoulder = (side * 0.20, -0.44, 0.90)
            elbow = (side * 0.26, -0.63, 0.55)
            wrist = (side * 0.27, -0.86, 0.16)
        deltoid = sphere(f"Deltoid{side}",
                         (shoulder[0], shoulder[1] + 0.005, shoulder[2] + 0.01),
                         (0.078, 0.074, 0.082), 48, 32)
        deltoid.data.materials.append(jacket)
        arm_objects.append(deltoid)
        upper = tube(f"UpperArm{side}", [shoulder, elbow],
                     [(0.068, 0.062), (0.052, 0.050)], 28)
        smooth(upper, 1)
        upper.data.materials.append(jacket)
        arm_objects.append(upper)
        forearm = tube(f"Forearm{side}", [elbow, wrist],
                       [(0.055, 0.052), (0.044, 0.040)], 28)
        smooth(forearm, 1)
        forearm.data.materials.append(jacket)
        arm_objects.append(forearm)
        # Cuff: a rolled band at the wrist.
        cuff_center = Vector(wrist) + (Vector(wrist) - Vector(elbow)).normalized() * 0.01
        cuff = tube(f"Cuff{side}",
                    [tuple(cuff_center - Vector((0, 0, 0.02))),
                     tuple(cuff_center + Vector((0, 0, 0.02)))],
                    [(0.052, 0.048), (0.050, 0.046)], 24)
        cuff.data.materials.append(jacket)
        arm_objects.append(cuff)
        hand_parts = build_hand(side, wrist, 1.0, 1.0 if pose == "stand" else 0.25)
        if pose == "crawl":
            # Fingers splayed on the floor: rotate the hand so the palm faces down.
            for obj in hand_parts:
                obj.rotation_euler = (math.radians(82), 0, 0)
        arm_objects.extend(hand_parts)
    parts.extend(arm_objects)
    parts.extend(build_legs(pose, trousers, rng))
    parts.extend(build_uniform_details(pose, jacket, trousers, rng))

    # Blood on the hands, chest and knees.
    for i, (center, scale) in enumerate([
        ((0.28, -0.88 if pose == "crawl" else 0.02, 0.13 if pose == "crawl" else 0.91),
         (0.040, 0.020, 0.035)),
        ((-0.19, -0.46 if pose == "crawl" else -0.11, 0.88 if pose == "crawl" else 1.30),
         (0.035, 0.020, 0.050)),
        ((0.13, -0.02, 0.50), (0.045, 0.025, 0.060)),
    ]):
        wound = rough_ellipsoid(f"BloodPatch{i}", center, scale, 0.28, seed + i)
        wound.data.materials.append(blood)
        parts.append(wound)
    return parts


def build_legs(pose: str, material, rng) -> list[bpy.types.Object]:
    parts = []
    for side in (-1, 1):
        if pose == "stand":
            hip = (side * 0.095, 0.0, 0.92)
            knee = (side * 0.105, -0.005, 0.52)
            ankle = (side * 0.105, 0.00, 0.12)
        else:
            hip = (side * 0.105, 0.18, 0.60)
            knee = (side * 0.13, 0.62, 0.20)
            ankle = (side * 0.15, 0.94, 0.11)
        thigh = tube(f"Thigh{side}", [hip, knee],
                     [(0.098, 0.090), (0.072, 0.070)], 32)
        smooth(thigh, 1)
        displace_noise(thigh, 0.18, 0.008)
        thigh.data.materials.append(material)
        parts.append(thigh)
        shin = tube(f"Shin{side}", [knee, ankle],
                    [(0.072, 0.068), (0.052, 0.050)], 32)
        smooth(shin, 1)
        displace_noise(shin, 0.16, 0.007)
        shin.data.materials.append(material)
        parts.append(shin)
        # Trousers bloused over the boot top.
        blouse_z = 0.30 if pose == "stand" else 0.40
        blouse = loft(f"Blouse{side}", [
            ring_xy(side * 0.105, 0.0, blouse_z, 0.075, 0.072, 32),
            ring_xy(side * 0.105, 0.0, blouse_z - 0.045, 0.070, 0.068, 32),
        ], False, False)
        blouse.data.materials.append(material)
        parts.append(blouse)
        # Boot sits over the shin.
        parts.extend(build_boot(f"Boot{side}", side * 0.105, 0.0 if pose == "stand" else 0.94))
        # Cargo pocket on the thigh.
        pocket = rounded_box(f"CargoPocket{side}",
                             (side * 0.14, -0.075, 0.66 if pose == "stand" else 0.38),
                             (0.026, 0.040, 0.055), 0.008, 1)
        pocket.data.materials.append(material)
        parts.append(pocket)
    return parts


def build_body_v2(male: bool, pose: str, seed: int) -> list[bpy.types.Object]:
    """Clothed body as one fused surface per garment, not a bundle of tubes."""
    print("STAGE body start", flush=True)
    rng = random.Random(seed)
    skin = skin_material()
    jacket = cloth_material("Jacket", (0.118, 0.135, 0.075), 0.52)
    trousers = cloth_material("Trousers", (0.102, 0.122, 0.068), 0.70)
    blood = blood_material()
    parts = []

    upper: list[bpy.types.Object] = []
    lower: list[bpy.types.Object] = []
    extras: list[bpy.types.Object] = []
    hands: list[bpy.types.Object] = []

    if pose == "stand":
        torso = loft("JacketTorso", [
            ring_xy(0, 0.005, 0.86, 0.180, 0.124, 64),
            ring_xy(0, 0.005, 0.94, 0.175, 0.120, 64),
            ring_xy(0, 0.000, 1.02, 0.166, 0.118, 64),
            ring_xy(0, 0.000, 1.10, 0.170, 0.124, 64),
            ring_xy(0, 0.000, 1.20, 0.190, 0.145, 64),
            ring_xy(0, 0.000, 1.30, 0.212, 0.164, 64),
            ring_xy(0, 0.000, 1.39, 0.224, 0.168, 64),
            ring_xy(0, 0.000, 1.45, 0.218, 0.154, 64),
            ring_xy(0, 0.000, 1.50, 0.166, 0.120, 64),
        ])
        smooth(torso, 1)
        upper.append(torso)
        # Trapezius: stops the neck from rising out of the collar like a stick.
        upper.append(sphere("Trapezius", (0, 0.020, 1.462), (0.108, 0.082, 0.058), 48, 32))
        seat = loft("TrouserSeat", [
            ring_xy(0, 0.008, 0.95, 0.160, 0.113, 48),
            ring_xy(0, 0.008, 0.87, 0.169, 0.120, 48),
            ring_xy(0, 0.008, 0.79, 0.158, 0.115, 48),
        ])
        smooth(seat, 1)
        lower.append(seat)
        for side in (-1, 1):
            shoulder = (side * 0.212, 0.000, 1.445)
            elbow = (side * 0.278, 0.012, 1.180)
            wrist = (side * 0.305, 0.032, 0.935)
            deltoid = sphere(f"Deltoid{side}", (shoulder[0], 0.0, 1.438),
                             (0.084, 0.080, 0.086), 48, 32)
            upper.append(deltoid)
            upper.append(tube(f"UpperArm{side}",
                              [shoulder, (side * 0.248, 0.008, 1.31), elbow],
                              [(0.077, 0.072), (0.072, 0.068), (0.058, 0.054)], 28))
            upper.append(tube(f"Forearm{side}",
                              [elbow, (side * 0.292, 0.020, 1.055), wrist],
                              [(0.062, 0.057), (0.057, 0.053), (0.046, 0.042)], 28))
            hands.extend(build_hand(side, wrist, 1.10, 1.0))
            hip = (side * 0.100, 0.0, 0.90)
            knee = (side * 0.112, -0.012, 0.50)
            ankle = (side * 0.110, 0.0, 0.115)
            lower.append(tube(f"Thigh{side}",
                              [hip, (side * 0.112, -0.008, 0.70), knee],
                              [(0.108, 0.102), (0.098, 0.094), (0.082, 0.079)], 32))
            lower.append(tube(f"Shin{side}",
                              [knee, (side * 0.112, 0.005, 0.34), ankle],
                              [(0.086, 0.081), (0.078, 0.074), (0.060, 0.058)], 32))
            lower.append(loft(f"Blouse{side}", [
                ring_xy(side * 0.108, 0.0, 0.30, 0.076, 0.073, 32),
                ring_xy(side * 0.108, 0.0, 0.25, 0.070, 0.068, 32),
            ], False, False))
            extras.extend(build_boot(f"Boot{side}", side * 0.108, 0.0))
    else:
        spine = [
            (0, 0.20, 0.62), (0, 0.06, 0.72), (0, -0.12, 0.86),
            (0, -0.30, 0.98), (0, -0.44, 1.06), (0, -0.50, 1.08),
        ]
        torso = tube("JacketTorso", spine,
                     [(0.150, 0.108), (0.162, 0.115), (0.176, 0.122),
                      (0.190, 0.132), (0.194, 0.138), (0.160, 0.112)], 64)
        smooth(torso, 1)
        upper.append(torso)
        seat = sphere("TrouserSeat", (0, 0.19, 0.61), (0.163, 0.125, 0.115), 48, 32)
        lower.append(seat)
        for side in (-1, 1):
            shoulder = (side * 0.185, -0.44, 1.050)
            elbow = (side * 0.245, -0.63, 0.60)
            wrist = (side * 0.255, -0.86, 0.15)
            upper.append(sphere(f"Deltoid{side}", (shoulder[0], shoulder[1], shoulder[2]),
                                (0.072, 0.070, 0.076), 48, 32))
            upper.append(tube(f"UpperArm{side}", [shoulder, elbow],
                              [(0.064, 0.060), (0.050, 0.047)], 28))
            upper.append(tube(f"Forearm{side}", [elbow, wrist],
                              [(0.052, 0.049), (0.042, 0.039)], 28))
            hands.extend(build_hand(side, wrist, 1.0, 0.15))
            hip = (side * 0.105, 0.19, 0.59)
            knee = (side * 0.125, 0.61, 0.20)
            ankle = (side * 0.135, 0.93, 0.115)
            lower.append(tube(f"Thigh{side}", [hip, knee],
                              [(0.098, 0.090), (0.074, 0.070)], 32))
            lower.append(tube(f"Shin{side}", [knee, ankle],
                              [(0.072, 0.068), (0.054, 0.052)], 32))
            boot = build_boot(f"Boot{side}", side * 0.135, 0.93)
            for obj in boot:
                obj.rotation_euler = (math.radians(70), 0, 0)
                obj.location = (0, 0.93, 0.10)
            extras.extend(boot)

    print("STAGE fuse top", flush=True)
    top = fuse(upper, "Jacket", 0.010, 10, 1)
    print("STAGE fused top", flush=True)
    top.data.materials.append(jacket)
    displace_noise(top, 0.22, 0.006)
    displace_noise(top, 0.045, 0.0022, "Clouds2", 1)
    print("STAGE apply top", flush=True)
    apply_modifiers(top)
    print("STAGE folds top verts", len(top.data.vertices), flush=True)
    cloth_folds(top, 0.0038, seed)
    print("STAGE folded top", flush=True)
    bpy.ops.object.shade_smooth()
    parts.append(top)

    print("STAGE fuse bottom", flush=True)
    bottom = fuse(lower, "Trousers", 0.010, 9, 1)
    print("STAGE fused bottom", flush=True)
    bottom.data.materials.append(trousers)
    displace_noise(bottom, 0.18, 0.006)
    displace_noise(bottom, 0.040, 0.0022, "Clouds2", 1)
    print("STAGE apply bottom", flush=True)
    apply_modifiers(bottom)
    print("STAGE folds bottom verts", len(bottom.data.vertices), flush=True)
    cloth_folds(bottom, 0.0042, seed + 1)
    print("STAGE folded bottom", flush=True)
    bpy.ops.object.shade_smooth()
    parts.append(bottom)

    # Cuffs, cargo pockets and hands stay crisp: they are sewn-on pieces, not remeshed cloth.
    for side in (-1, 1):
        if pose == "stand":
            wrist_z = 0.935
            cuff = loft(f"Cuff{side}", [
                ring_xy(side * 0.300, 0.032, wrist_z + 0.03, 0.049, 0.047, 28),
                ring_xy(side * 0.300, 0.032, wrist_z - 0.005, 0.047, 0.045, 28),
            ], False, False)
            cargo_z = 0.64
            cargo_x = side * 0.155
            cargo_y = -0.088
        else:
            cuff = loft(f"Cuff{side}", [
                ring_xy(side * 0.255, -0.835, 0.175, 0.048, 0.046, 28),
                ring_xy(side * 0.255, -0.860, 0.145, 0.046, 0.044, 28),
            ], False, False)
            cargo_z = 0.40
            cargo_x = side * 0.165
            cargo_y = 0.56
        cuff.data.materials.append(jacket)
        parts.append(cuff)
        cargo = rounded_box(f"CargoPocket{side}", (cargo_x, cargo_y, cargo_z),
                            (0.035, 0.020, 0.052), 0.010, 1)
        cargo.data.materials.append(trousers)
        parts.append(cargo)
    parts.extend(hands)
    parts.extend(extras)

    print("STAGE head", flush=True)
    head_parts = build_head_sculpt(male, seed, pose == "crawl")
    print("STAGE head done", flush=True)
    head_pivot = Vector((0, 0, 1.49))
    head_scale = 1.34
    grow = Matrix.Translation(head_pivot) @ Matrix.Scale(head_scale, 4) @ Matrix.Translation(-head_pivot)
    lower = Matrix.Translation(Vector((0, 0, -0.035)))
    for obj in head_parts:
        obj.matrix_world = lower @ grow @ obj.matrix_world
    if pose == "crawl":
        pivot = Vector((0, -0.005, 1.64))
        target = Vector((0, -0.50, 1.115))
        move = Matrix.Translation(target) @ Matrix.Rotation(math.radians(-28), 4, "X") @ Matrix.Translation(-pivot)
        for obj in head_parts:
            obj.matrix_world = move @ obj.matrix_world
        # Wet hair still falls downward: undo the head's pitch for the hair only.
        hair_pivot = Vector((0, -0.50, 1.14))
        hair_down = (Matrix.Translation(hair_pivot) @ Matrix.Rotation(math.radians(28), 4, "X")
                     @ Matrix.Translation(-hair_pivot))
        for obj in head_parts:
            if obj.name.startswith("Hair"):
                obj.matrix_world = hair_down @ obj.matrix_world
    parts.extend(head_parts)
    parts.extend(build_uniform_details(pose, jacket, trousers, rng))

    # Blood soaks into the cloth: decals are shrink-wrapped to the final garment,
    # so they curve with the folds instead of hovering like red discs.
    stains = [
        (top, (0.105, -0.12, 1.275), 0.055, 1.3),
        (top, (-0.120, -0.10, 1.160), 0.042, 0.8),
        (top, (0.175, -0.02, 1.395), 0.036, 1.0),
        (bottom, (0.112, -0.075, 0.505), 0.058, 0.9),
        (bottom, (-0.118, -0.065, 0.610), 0.046, 1.4),
        (bottom, (0.150, 0.02, 0.400), 0.040, 1.0),
    ]
    print("STAGE stains", flush=True)
    muscle = simple_material("TornMuscle", (0.055, 0.012, 0.010), 0.38)
    # Ragged tears only where the reference has them: knees, one thigh, one elbow.
    tears = [
        (bottom, (0.112, -0.090, 0.505), 0.048),
        (bottom, (-0.116, -0.084, 0.620), 0.036),
        (top, (-0.252, -0.060, 1.165), 0.026),
    ]
    for i, (target, center, radius) in enumerate(tears):
        inner = tear_hole(target, center, radius, muscle, seed + i)
        if inner is not None:
            parts.append(inner)
    return parts


def build_uniform_details(pose: str, jacket, trousers, rng) -> list[bpy.types.Object]:
    parts = []
    metal = simple_material("Buttons", (0.18, 0.15, 0.09), 0.35, 0.7)
    leather = simple_material("Belt", (0.055, 0.038, 0.025), 0.50)
    if pose == "stand":
        # Collar.
        collar_outer = loft("Collar", [
            ring_xy(0, 0.004, 1.485, 0.070, 0.060, 48),
            ring_xy(0, 0.006, 1.545, 0.082, 0.072, 48),
        ], False, False)
        solid = collar_outer.modifiers.new("Solid", "SOLIDIFY")
        solid.thickness = 0.008
        collar_outer.data.materials.append(jacket)
        parts.append(collar_outer)
        # Epaulettes sit across the shoulders, as on the reference uniform.
        for side in (-1, 1):
            strap = rounded_box(f"Epaulette{side}", (side * 0.128, 0.0, 1.476),
                                (0.062, 0.030, 0.006), 0.004, 1)
            strap.rotation_euler = (0, side * math.radians(8), 0)
            strap.data.materials.append(jacket)
            parts.append(strap)
        # Belt.
        belt = loft("Belt", [
            ring_xy(0, 0, 0.965, 0.172, 0.124, 64),
            ring_xy(0, 0, 1.025, 0.172, 0.124, 64),
        ], False, False)
        belt.data.materials.append(leather)
        parts.append(belt)
        buckle = rounded_box("Buckle", (0, -0.128, 1.0), (0.035, 0.010, 0.022), 0.004, 1)
        buckle.data.materials.append(metal)
        parts.append(buckle)
        # Buttons and chest pockets.
        for i in range(5):
            button = sphere(f"Button{i}", (0, -0.162, 1.05 + i * 0.083),
                            (0.008, 0.004, 0.008), 24, 16)
            button.data.materials.append(metal)
            parts.append(button)
        for side in (-1, 1):
            pocket = rounded_box(f"ChestPocket{side}",
                                 (side * 0.100, -0.160, 1.305),
                                 (0.051, 0.010, 0.055), 0.005, 1)
            pocket.data.materials.append(jacket)
            parts.append(pocket)
            flap = rounded_box(f"ChestFlap{side}",
                               (side * 0.100, -0.170, 1.350),
                               (0.053, 0.008, 0.014), 0.004, 1)
            flap.data.materials.append(jacket)
            parts.append(flap)
    else:
        # Crawler: belt across the horizontal waist and the collar at the neck.
        belt = tube("Belt", [(0, 0.19, 0.62), (0, 0.08, 0.68)],
                    [(0.170, 0.125), (0.172, 0.126)], 48)
        belt.data.materials.append(leather)
        parts.append(belt)
        for i in range(4):
            button = sphere(f"Button{i}", (0, -0.45 + i * 0.06, 0.96 - i * 0.005),
                            (0.008, 0.004, 0.008), 24, 16)
            button.data.materials.append(metal)
            parts.append(button)
    return parts


def blood_material() -> bpy.types.Material:
    mat = new_material("Blood", (0.115, 0.012, 0.010), 0.30)
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Subsurface Weight"].default_value = 0.15
    bsdf.inputs["Subsurface Radius"].default_value = (0.3, 0.04, 0.02)
    noise = mat.node_tree.nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 45.0
    bump = mat.node_tree.nodes.new("ShaderNodeBump")
    bump.inputs["Strength"].default_value = 0.4
    bump.inputs["Distance"].default_value = 0.003
    mat.node_tree.links.new(noise.outputs["Fac"], bump.inputs["Height"])
    mat.node_tree.links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])
    return mat


# ── Studio, camera and render ────────────────────────────────────────────────

TEXTURES: dict[str, str] = {}


def apply_textures(objects) -> None:
    skin = image_material("SkinTextured", TEXTURES, "skin_albedo", "skin_normal", "skin_rough",
                         sss=0.18, uv_scale=3.0)
    jacket = image_material("JacketTextured", TEXTURES, "cloth_albedo", "cloth_normal", "cloth_rough",
                            color=(0.80, 0.85, 0.72), uv_scale=2.4)
    trousers = image_material("TrousersTextured", TEXTURES, "cloth_albedo", "cloth_normal", "cloth_rough",
                              color=(0.68, 0.74, 0.60), uv_scale=2.0)
    leather = image_material("LeatherTextured", TEXTURES, "leather_albedo", "leather_normal", None,
                             uv_scale=2.5)
    hair = image_material("HairTextured", TEXTURES, "hair_albedo", "hair_normal" if "hair_normal" in TEXTURES else "",
                          None, uv_scale=1.0)
    for obj in objects:
        if obj.type != "MESH":
            continue
        for slot in obj.material_slots:
            material = slot.material
            if material is None:
                continue
            name = material.name
            if name.startswith("ZombieSkin") or name.startswith("EarSkin") or name.startswith("Lips"):
                slot.material = skin
            elif name.startswith("Jacket"):
                slot.material = jacket
            elif name.startswith("Trousers"):
                slot.material = trousers
            elif "Leather" in name or name.startswith("Belt") or name.startswith("Boot"):
                slot.material = leather
            elif name.startswith("Hair"):
                slot.material = hair
        if len(obj.data.polygons) > 400 and not obj.data.uv_layers:
            uv_unwrap(obj)


def build_studio() -> None:
    # Backdrop and floor.
    bpy.ops.mesh.primitive_plane_add(size=12, location=(0, 1.6, 0))
    backdrop = bpy.context.object
    backdrop.rotation_euler = (math.radians(90), 0, 0)
    backdrop.name = "Backdrop"
    bg = new_material("BackdropMat", (0.045, 0.048, 0.050), 0.75)
    backdrop.data.materials.append(bg)
    bpy.ops.mesh.primitive_plane_add(size=12, location=(0, 0, 0))
    floor = bpy.context.object
    floor.name = "Floor"
    floor.data.materials.append(bg)

    world = bpy.data.worlds.new("World")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.020, 0.022, 0.024, 1)
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.35
    bpy.context.scene.world = world

    def light(name, location, energy, size, color):
        data = bpy.data.lights.new(name, "AREA")
        data.energy = energy
        data.shape = "SQUARE"
        data.size = size
        data.color = color
        obj = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(obj)
        obj.location = location
        direction = Vector((0, 0, 1.1)) - Vector(location)
        obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
        return obj

    light("Key", (-2.2, -2.6, 3.0), 720, 2.2, (1.0, 0.84, 0.70))
    light("Fill", (2.6, -2.0, 1.6), 90, 3.2, (0.58, 0.72, 0.92))
    light("Rim", (1.4, 2.8, 2.4), 460, 2.0, (0.55, 0.70, 0.95))
    light("Rim", (0.7, 2.6, 2.4), 340, 2.0, (1.0, 0.74, 0.56))
    light("Bounce", (0.0, -1.2, 0.15), 45, 2.0, (0.6, 0.55, 0.45))


def add_camera(view: str, target_z: float = 1.0) -> None:
    data = bpy.data.cameras.new("Camera")
    data.lens = 62
    data.sensor_width = 36
    cam = bpy.data.objects.new("Camera", data)
    bpy.context.collection.objects.link(cam)
    positions = {
        "front": (0.0, -3.6, 1.05),
        "three_quarter": (1.15, -3.15, 1.12),
        "side": (3.6, -0.1, 1.05),
        "back": (0.0, 3.6, 1.05),
        "closeup": (0.38, -1.20, 1.64),
    }
    cam.location = positions.get(view, positions["three_quarter"])
    if view == "closeup":
        data.lens = 85
        target_z = 1.645
    look = Vector((0, 0, target_z))
    direction = look - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    bpy.context.scene.camera = cam


def render(path: str, size: int = 1024, samples: int = 64) -> None:
    scene = bpy.context.scene
    scene.render.resolution_x = size
    scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.filepath = os.path.abspath(path)
    scene.render.image_settings.file_format = "PNG"
    if scene.render.engine == "BLENDER_EEVEE":
        scene.eevee.taa_render_samples = samples
    bpy.ops.render.render(write_still=True)
    print("RENDERED", scene.render.filepath)


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=["male", "female"], default="male")
    parser.add_argument("--pose", choices=["stand", "crawl"], default="stand")
    parser.add_argument("--view", default="three_quarter")
    parser.add_argument("--out", default="tools/zombie_studio/out")
    parser.add_argument("--size", type=int, default=1024)
    parser.add_argument("--samples", type=int, default=64)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])

    os.makedirs(args.out, exist_ok=True)
    global TEXTURES
    TEXTURES = generate_textures(os.path.join(args.out, "textures"), seed=args.seed + 3)
    reset_scene()
    build_studio()
    parts = build_body_v2(args.variant == "male", args.pose, args.seed)
    for obj in parts:
        if obj.type == "MESH" and not obj.data.materials:
            obj.data.materials.append(skin_material())
    apply_textures(parts)
    add_camera(args.view)
    name = f"{args.variant}_{args.pose}_{args.view}.png"
    render(os.path.join(args.out, name), args.size, args.samples)


if __name__ == "__main__":
    main()
