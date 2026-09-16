"""Read-only probe: how is Hair bound, and which bone should it actually ride?

Answers three questions before anything is re-exported, because this bug has now been diagnosed
wrong three times:

  1. How is Hair attached -- bone parent, vertex groups, or both?
  2. Does the bone it is parented to get BAKED? collect_bones() only bakes bones that appear as a
     vertex group on some mesh, so a control bone can be a perfectly valid parent and still end up
     folded onto the shared static slot, which is what left the hair frozen last time.
  3. Which deform bone actually tracks the head through the animation?

Never saves the .blend.
"""
import os
import sys

import bpy
from mathutils import Vector

sys.path.append(os.path.dirname(os.path.abspath(__file__)))
import build_idle_animation as idle           # noqa: E402

rig = idle.rig
scene = bpy.context.scene


def deform_bones_referenced():
    """Exactly what export_skinned.collect_bones() would bake."""
    used = set()
    for obj in bpy.data.objects:
        if obj.type != 'MESH':
            continue
        for vg in obj.vertex_groups:
            if vg.name in rig.pose.bones:
                used.add(vg.name)
    return used


def main():
    print("<<< PROBE blend:", bpy.data.filepath)

    meshes = idle.character_meshes()
    print("<<< meshes:", meshes)

    baked = deform_bones_referenced()
    print("<<< bones collect_bones() would bake:", len(baked))

    for name in meshes:
        obj = bpy.data.objects[name]
        groups = [g.name for g in obj.vertex_groups]
        print("<<< %-8s parent=%s type=%s bone=%r groups=%d mods=%s"
              % (name, obj.parent.name if obj.parent else None, obj.parent_type,
                 obj.parent_bone, len(groups),
                 [m.type for m in obj.modifiers]))

    hair = bpy.data.objects.get("Hair")
    if hair is None:
        print("<<< no Hair object")
        return

    parent_bone = hair.parent_bone
    print("<<< Hair parent_bone=%r  in rig=%s  baked=%s"
          % (parent_bone, parent_bone in rig.pose.bones, parent_bone in baked))

    # Which groups actually carry weight on Hair, and are they baked?
    weighted = {}
    for v in hair.data.vertices:
        for g in v.groups:
            if g.weight > 0:
                nm = hair.vertex_groups[g.group].name
                weighted[nm] = weighted.get(nm, 0.0) + g.weight
    print("<<< Hair weighted groups (name, mass, baked?):")
    for nm, mass in sorted(weighted.items(), key=lambda kv: -kv[1])[:8]:
        print("      %-22s %9.1f  baked=%s" % (nm, mass, nm in baked))

    # Head-ish deform bone candidates.
    candidates = [b for b in rig.pose.bones.keys()
                  if "head" in b.lower() or "spine.006" in b.lower()]
    print("<<< head-ish bones:", sorted(candidates))

    # Bake, then measure which candidates actually move, and how closely each tracks the
    # control bone the hair is parented to.
    idle.clean()
    idle.bake()

    def sample(names, frames):
        out = {n: [] for n in names}
        for f in frames:
            scene.frame_set(f)
            bpy.context.view_layer.update()
            for n in names:
                out[n].append(rig.pose.bones[n].matrix.copy())
        return out

    frames = [1, 40, 75, 150, 300, 435]
    watch = sorted(set(candidates) | ({parent_bone} if parent_bone in rig.pose.bones else set()))
    mats = sample(watch, frames)

    print("<<< motion over the bake (max translation from frame 1, cm):")
    for n in watch:
        base = mats[n][0].translation
        move = max((m.translation - base).length for m in mats[n]) * 100
        print("      %-22s %7.2f cm   baked=%s" % (n, move, n in baked))

    if parent_bone in rig.pose.bones:
        # The replacement must move the hair the way the parent bone does. Compare the DELTA each
        # bone applies relative to its own rest pose -- that is what the exported skinning matrix
        # encodes -- rather than comparing world positions, which differ simply because the bones
        # sit in different places.
        print("<<< how closely each baked bone reproduces %r's motion:" % parent_bone)
        rest = {n: rig.data.bones[n].matrix_local for n in watch}
        anchor = hair.matrix_world.translation.copy()
        ref = [m @ rest[parent_bone].inverted() for m in mats[parent_bone]]
        for n in watch:
            if n not in baked:
                continue
            delta = [m @ rest[n].inverted() for m in mats[n]]
            worst = max(((a @ anchor) - (b @ anchor)).length
                        for a, b in zip(ref, delta)) * 100
            print("      %-22s worst disagreement at the hair %7.3f cm" % (n, worst))


main()
