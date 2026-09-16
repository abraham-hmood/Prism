"""
Authors Aether's default idle -- standing, hands held together in front, gently swaying -- and
exports it as FBX.

WHY IT IS BUILT RATHER THAN CAPTURED. Every mp4-to-mocap route failed, so this is hand-authored:
an explicit rest pose solved with real anatomy, then a stack of low-amplitude oscillators laid over
it. The thing that separates this from a "basic" idle is that no two layers share a period or a
phase, several of them lag the layer below, and the whole set is built out of whole numbers of
cycles per loop so the last frame lands exactly on the first.

COORDINATE SYSTEM, measured off the rig rather than assumed: Blender is Z-up, she faces -Y, and her
left hand is +X. Her shoulder joint sits at z=1.391, hip at 0.935, eyes at 1.592, and both arm
segments are 0.241 long.
"""
import math
import os
import sys

import bpy
from mathutils import Matrix, Quaternion, Vector

ARGS = sys.argv[sys.argv.index("--") + 1:]
OUT_DIR = ARGS[0]
DO_FBX = "--fbx" in ARGS
POSE_ONLY = "--poseonly" in ARGS   # skip the bake; render the rest pose and stop

FPS = 30
LOOP = 360                      # 12 seconds
# The arrival beat: head lowered, then raised to camera. 2.5 seconds, played once.
INTRO = 75
TOTAL = INTRO + LOOP

# Blinks, as frame numbers inside the loop. Kept clear of both ends so no blink straddles the
# loop boundary, and spaced unevenly so they never fall into rhythm with the sway or the breath.
BLINK_FRAMES = (47, 143, 191, 296)
BLINK_LENGTH = 9

# Solved against the mesh at build time; see solve_lids.
LID_CLOSE = {}
rig = bpy.data.objects["blender file_Rigify"]
scene = bpy.context.scene

def character_meshes(textures=None):
    """The rig's renderable meshes, DISCOVERED rather than listed by name.

    The .blend is edited outside this pipeline, and a hardcoded list breaks the moment somebody
    renames something -- which is exactly what happened when `LongCurly` became `Hair` and the
    export died on a KeyError. Anything the rig deforms, that is not a control widget or the
    hide_render collider, and that carries a material this pipeline has a texture for, is part of
    her.
    """
    found = []
    for o in bpy.data.objects:
        if o.type != 'MESH' or o.hide_render:
            continue
        if o.name.startswith("WGT-") or "Collider" in o.name:
            continue
        if not any(m.type == 'ARMATURE' and m.object is rig for m in o.modifiers):
            continue
        if textures is not None and not any(
                slot and slot.name in textures for slot in o.data.materials):
            continue
        found.append(o.name)
    return found
# Shown in previews so pose checks are clothed; deliberately not exported, since custom clothing
# is coming later.
PREVIEW_ONLY = ["Bra", "Underwear_Bottoms"]


def clean():
    """Cloth and collision cannot be baked into bone animation and would simulate through every
    preview frame; the hair is skinned to DEF-spine.003..006 and follows the head without them."""
    for o in bpy.data.objects:
        if o.type == 'MESH':
            for m in list(o.modifiers):
                if m.type in ('CLOTH', 'COLLISION', 'SOFT_BODY'):
                    o.modifiers.remove(m)
    # MUTE ANYTHING REACHING OUTSIDE THE RIG. The .blend is a working file and carries an
    # in-progress motion-tracking setup -- PelvisTrack, StomachTrack, SpineTrack, NeckTrack and
    # HeadTrack empties bound to the spine with Copy Location/Rotation at full influence. Those
    # empties sit at the world origin, and with the armature scaled and offset they drag the whole
    # spine and shoulder chain about 19 cm out of place, which silently ruins every pose solved
    # against it: the elbow solver hunts a target it can no longer reach and the arm locks straight.
    #
    # Muted rather than removed, and this script never saves the .blend. Somebody else's rigging
    # experiment is not the exporter's to delete -- it just should not participate in a bake of an
    # animation that was authored by hand.
    muted = {}
    for pb in rig.pose.bones:
        for c in pb.constraints:
            target = getattr(c, "target", None)
            if target is not None and target is not rig and not c.mute:
                c.mute = True
                muted[target.name] = muted.get(target.name, 0) + 1
    if muted:
        print("<<< muted external constraints for this bake:",
              ", ".join("%s x%d" % (k, v) for k, v in sorted(muted.items())))

    # Start from a clean slate rather than on top of whatever the import left posed.
    if rig.animation_data:
        rig.animation_data_clear()
    for pb in rig.pose.bones:
        pb.location = (0, 0, 0)
        pb.rotation_quaternion = (1, 0, 0, 0)
        pb.rotation_euler = (0, 0, 0)
        pb.scale = (1, 1, 1)


def update():
    bpy.context.view_layer.update()


def world_head(name):
    """A bone's head in ARMATURE space, which is the only space this file should ever think in.

    IT USED TO RETURN WORLD SPACE, and that quietly tied every pose constant here to wherever the
    armature happened to sit in the .blend. When the rig was later scaled 3.8x and shifted along X
    for unrelated scene work, the elbow solver started reading positions around z=3.4 instead of
    1.17, hunted for a target that no longer existed in those units, and produced a pose that was
    nonsense. A pose is a property of the rig, not of where the rig is parked, so nothing in here
    multiplies by matrix_world any more. The exporter already worked in armature space and was
    unaffected.
    """
    return rig.pose.bones[name].head.copy()


def aim(name, target, up_hint):
    """Points a bone's Y axis at `target`, keeping its head where the parent put it.

    Rigify FK bones run along local Y, so aiming is the honest way to place a limb: it takes the
    joint position the parent already decided and only chooses a direction, which means a chain
    posed head-to-tail can never drift apart at the joints.
    """
    pb = rig.pose.bones[name]
    head = pb.head.copy()
    y = (Vector(target) - head)
    if y.length < 1e-6:
        return
    y.normalize()
    up = Vector(up_hint)
    z = up - y * up.dot(y)
    if z.length < 1e-5:
        z = Vector((0, 0, 1)) - y * y.z
    z.normalize()
    x = y.cross(z)
    basis = Matrix(((x.x, y.x, z.x, head.x),
                    (x.y, y.y, z.y, head.y),
                    (x.z, y.z, z.z, head.z),
                    (0.0, 0.0, 0.0, 1.0)))
    pb.matrix = basis
    update()


def place(name, position, aim_dir=None, up_hint=(0.0, 0.0, 1.0)):
    """Puts a control bone at a world position, optionally pointing its Y axis along `aim_dir`.

    DELIBERATELY DOES NOT re-evaluate the scene. `view_layer.update()` on a 724-bone rig carrying
    this many constraints and drivers costs a sizeable fraction of a second, and calling it once
    per placed bone turned a two-minute bake into one that ran past ten. The caller updates once,
    after everything a later read depends on has been written.
    """
    pb = rig.pose.bones[name]
    pos = Vector(position)
    if aim_dir is None:
        m = pb.matrix.copy()
        m.translation = pos
        pb.matrix = m
        return
    y = Vector(aim_dir).normalized()
    up = Vector(up_hint)
    z = up - y * up.dot(y)
    if z.length < 1e-5:
        z = Vector((0, 0, 1)) - y * y.z
    z.normalize()
    x = y.cross(z)
    basis = Matrix(((x.x, y.x, z.x, pos.x),
                    (x.y, y.y, z.y, pos.y),
                    (x.z, y.z, z.z, pos.z),
                    (0.0, 0.0, 0.0, 1.0)))
    pb.matrix = basis


def two_bone_elbow(shoulder, wrist, l1, l2, pole):
    """Exact elbow position for a two-bone chain, so the wrist lands where it was asked to.

    Solving it outright instead of leaning on Rigify's IK is what keeps the elbow from flipping:
    the pole only picks which side of the shoulder-to-wrist line the joint sits on, and there is no
    solver state to disagree with between frames.
    """
    s, w = Vector(shoulder), Vector(wrist)
    axis = w - s
    d = axis.length
    d = min(d, (l1 + l2) * 0.999)
    axis.normalize()
    a = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
    h = math.sqrt(max(l1 * l1 - a * a, 0.0))
    p = Vector(pole)
    perp = p - axis * p.dot(axis)
    if perp.length < 1e-5:
        perp = Vector((0, 0, 1)) - axis * axis.z
    perp.normalize()
    return s + axis * a + perp * h


def rot(name, x=0.0, y=0.0, z=0.0):
    """Additive local-space rotation in degrees, composed onto whatever the bone already carries."""
    pb = rig.pose.bones[name]
    pb.rotation_mode = 'QUATERNION'
    q = Quaternion((1, 0, 0, 0))
    if x: q = q @ Quaternion((1, 0, 0), math.radians(x))
    if y: q = q @ Quaternion((0, 1, 0), math.radians(y))
    if z: q = q @ Quaternion((0, 0, 1), math.radians(z))
    pb.rotation_quaternion = pb.rotation_quaternion @ q


UPPER = 0.241
FORE = 0.241

# Where the wrists want to be: in front of the lower belly, close enough together that the fingers
# meet. Her belly surface is around y=-0.10 at this height, so -0.17 holds the hands a hand's
# breadth clear of her rather than pressed against her.
# The two hands are deliberately NOT mirror images. Placed symmetrically they meet edge-on in the
# middle and their fingers pass straight through one another; offsetting the left hand forward and
# up by a few centimetres makes it rest ON the right instead of inside it.
WRIST_L = Vector((0.052, -0.170, 1.056))
WRIST_R = Vector((-0.044, -0.132, 1.018))

# Where the elbow wants to sit, as a distance out from the centreline. Her ribs are about 0.14
# wide at that height, so anything under ~0.19 drives the upper arm into her own torso.
ELBOW_X = 0.205
# Solved once at build time and reused every frame -- see solve_elbow.
ELBOW_ROT = {}

# Lip-corner lift for the resting smile, in degrees.
SMILE = 3.5


def build_rest_pose():
    # THE ARMS ARE DRIVEN ON IK, and that is a decision the rig made rather than a preference.
    # Its deform bones do not follow the ORG chain at all -- DEF-upper_arm copies `upper_arm_tweak`
    # and stretches to the next tweak, and the tweaks ride the IK chain in the rig's shipped state.
    # Flipping the IK_FK property does re-route them, but only once the drivers behind it are
    # re-evaluated, and that plumbing turned out to be fragile enough across a frame-by-frame bake
    # that the FK controls posed correctly while the mesh stayed in its A-pose.
    #
    # Driving the IK targets works in the rig's default state with nothing to tag or refresh. It
    # also turns out to be the better tool here: the follow-through in the hands is authored
    # directly as a lagged offset on the target, rather than inherited from the chest and hoped for.
    #
    # The arms are therefore posed entirely in pose_at(), which runs per frame.

    # A slight inward and forward roll of the shoulders: the difference between standing to
    # attention and standing quietly.
    rot("shoulder.L", x=-2.0, z=-3.2)
    rot("shoulder.R", x=-2.0, z=3.2)
    update()

    # Fingers: a soft natural curl, more at the tips than the knuckles, with the little finger
    # curled furthest. A flat hand is the single clearest sign of an unfinished pose.
    for side in "LR":
        # The draping hand keeps a softer curl than the one underneath it.
        curl = 0.78 if side == "L" else 1.0
        for finger, base in (("index", 10.0 * curl), ("middle", 12.0 * curl),
                             ("ring", 14.0 * curl), ("pinky", 17.0 * curl)):
            for seg, gain in ((1, 1.0), (2, 1.35), (3, 1.15)):
                name = "f_%s.0%d.%s" % (finger, seg, side)
                if name in rig.pose.bones:
                    rot(name, x=-base * gain)
        for seg, gain in ((1, 0.6), (2, 0.9), (3, 0.7)):
            name = "thumb.0%d.%s" % (seg, side)
            if name in rig.pose.bones:
                rot(name, x=-9.0 * gain)

    solve_elbow()

    # A resting smile, small enough to read as expression rather than as a grin. It lives in the
    # REST pose so the idle carries it too -- if it belonged only to the intro, her face would fall
    # the moment the loop started.
    for corner, sign in (("lips.L", 1.0), ("lips.R", -1.0)):
        if corner in rig.pose.bones:
            rot(corner, z=SMILE * sign)

    # Spine: the faintest forward settle and a soft knee, so she is standing rather than posed.
    rot("chest", x=1.1)
    rot("head", x=2.4)
    rot("neck", x=1.0)
    for side in "LR":
        rot("thigh_fk.%s" % side, x=-1.2)
        rot("shin_fk.%s" % side, x=2.0)
    update()


def solve_lids():
    """Finds how to actually close an eyelid, by moving it and watching the mesh.

    ROTATION DOES NOTHING HERE, which is the whole reason this exists. Rigify's face controls are
    TWEAK bones on a b-bone chain: they are positional handles, so rotating `lid.T.L` moves the lid
    by exactly zero and an earlier version of this dutifully reported "does not close on X" for both
    eyes. The lid closes when the chain is TRANSLATED, so this sweeps the three local axes in both
    directions, keeps whichever one lowers the lid, and then scales it to travel the eye's own
    height -- measured from the eyeball, which spans z 1.575..1.608.
    """
    eye_top, eye_bottom = 1.6079, 1.5754
    target = (eye_top - eye_bottom) * 0.82           # nearly shut, not clenched
    probe_step = 0.01

    for side in "LR":
        chain = [n for n in ("lid.T.%s" % side, "lid.T.%s.001" % side,
                             "lid.T.%s.002" % side, "lid.T.%s.003" % side)
                 if n in rig.pose.bones]
        lower = [n for n in ("lid.B.%s" % side, "lid.B.%s.001" % side,
                             "lid.B.%s.002" % side, "lid.B.%s.003" % side)
                 if n in rig.pose.bones]
        probe = "DEF-lid.T.%s.001" % side
        if not chain or probe not in rig.pose.bones:
            print("<<< no lid chain for", side)
            continue

        for n in chain:
            rig.pose.bones[n].location = (0.0, 0.0, 0.0)
        update()
        rest_z = world_head(probe).z

        best = None
        for axis in range(3):
            for sign in (1.0, -1.0):
                offset = [0.0, 0.0, 0.0]
                offset[axis] = sign * probe_step
                for n in chain:
                    rig.pose.bones[n].location = offset
                update()
                drop = rest_z - world_head(probe).z      # positive = lid travelled down
                for n in chain:
                    rig.pose.bones[n].location = (0.0, 0.0, 0.0)
                if best is None or drop > best[0]:
                    best = (drop, axis, sign)
        update()

        drop, axis, sign = best
        if drop <= 1e-5:
            print("<<< lid.%s will not close on any axis (best drop %.5f); blinks disabled"
                  % (side, drop))
            continue
        # The lid travels linearly with the handle over this range, so one probe sets the scale.
        magnitude = sign * probe_step * (target / drop)
        LID_CLOSE[side] = (axis, magnitude, tuple(chain), tuple(lower))
        print("<<< lid.%s closes on local axis %d, offset %+.4f (probe drop %.4f -> target %.4f)"
              % (side, axis, magnitude, drop, target))


def blink_amount(frame):
    """0 open, 1 shut. Fast down, a beat closed, a slower lift -- a real blink is not symmetric."""
    for start in BLINK_FRAMES:
        d = frame - start
        if 0.0 <= d < BLINK_LENGTH:
            if d < 3.0:
                return d / 3.0
            if d < 4.0:
                return 1.0
            return max(0.0, 1.0 - (d - 4.0) / (BLINK_LENGTH - 4.0))
    return 0.0


def apply_lids(amount):
    """Additive, so a blink can ride on top of the lowered lids the intro asks for."""
    if amount <= 0.0:
        return
    for side, (axis, magnitude, chain, lower) in LID_CLOSE.items():
        for name in chain:
            pb = rig.pose.bones[name]
            loc = list(pb.location)
            loc[axis] += magnitude * amount
            pb.location = loc
        # The lower lid rises a little to meet it, which is what stops a blink reading as a shutter.
        for name in lower:
            pb = rig.pose.bones[name]
            loc = list(pb.location)
            loc[axis] -= magnitude * amount * 0.22
            pb.location = loc


def intro_pose(t):
    """The arrival, as a decaying offset ON TOP of the idle rather than a separate pose.

    THIS IS WHAT MAKES THE JOIN INVISIBLE. The intro is not a clip that hands over to the loop; it is
    the loop with an extra pose added and faded out. At t=1 the offset is exactly zero, so the last
    intro frame IS the idle at that phase -- there is no blend to tune and no seam to find. It also
    means she is already breathing and swaying while she arrives, instead of being frozen until the
    loop takes over.

    The decay is eased hard: most of the movement happens early and the last part of it settles
    slowly, which is how weight actually arrives.
    """
    d = (1.0 - t) ** 2.2

    # Head lowered, coming up to camera. The reference frames are a full-body studio shot, but at
    # Prism's crop only this is visible -- which is why the walk in them is not reproduced.
    rot("head", x=d * 19.0, z=d * 2.5)
    rot("neck", x=d * 7.5)
    rot("chest", x=d * 2.2)
    rot("shoulder.L", x=d * 1.6)
    rot("shoulder.R", x=d * 1.6)

    # Gaze follows the head down, then comes up fractionally later, which reads as her looking up
    # rather than her head simply rotating.
    gaze = (1.0 - min(1.0, t * 1.18)) ** 2.0
    if "eyes" in rig.pose.bones:
        pb = rig.pose.bones["eyes"]
        pb.location = (0.0, 0.0, -gaze * 0.085)

    # Lids ride partly down with the lowered gaze and open as she lifts.
    apply_lids(min(1.0, gaze * 0.55))

    # The mouth arrives at the resting smile the idle carries, so nothing pops when the loop starts.
    for corner, sign in (("lips.L", 1.0), ("lips.R", -1.0)):
        if corner in rig.pose.bones:
            rot(corner, z=-SMILE * d * sign)


def solve_elbow():
    """Finds the IK-parent roll that puts each elbow where it belongs, by measuring the mesh.

    Guessing this angle is hopeless -- it depends on the shoulder pose, the wrist target and the
    rig's own axes at once -- but measuring it is cheap: swing the parent, read where DEF-forearm
    actually ended up, keep the best. A dozen evaluations settles it, and the answer is reused for
    every frame of the bake.
    """
    for side, sign in (("L", 1.0), ("R", -1.0)):
        place("hand_ik.%s" % side,
              WRIST_L if side == "L" else WRIST_R,
              Vector((-0.60 * sign, -0.42, -0.68)), (0.0, -1.0, 0.25))
    update()

    for side, sign in (("L", 1.0), ("R", -1.0)):
        pb = rig.pose.bones["upper_arm_ik.%s" % side]
        pb.rotation_mode = 'QUATERNION'
        best, best_deg, lo, hi = 1e9, 0.0, [], []
        for deg in range(-80, 81, 8):
            pb.rotation_quaternion = Quaternion((0, 1, 0), math.radians(deg))
            update()
            e = world_head("DEF-forearm.%s" % side)
            lo.append(e.x)
            err = abs(e.x - ELBOW_X * sign) + abs(e.y - 0.015) * 0.6
            if err < best:
                best, best_deg = err, float(deg)
        # Refine around the winner, since 8 degrees is coarse for something this visible.
        for step in (4.0, 2.0, 1.0):
            for deg in (best_deg - step, best_deg + step):
                pb.rotation_quaternion = Quaternion((0, 1, 0), math.radians(deg))
                update()
                e = world_head("DEF-forearm.%s" % side)
                err = abs(e.x - ELBOW_X * sign) + abs(e.y - 0.015) * 0.6
                if err < best:
                    best, best_deg = err, deg
        ELBOW_ROT[side] = best_deg
        pb.rotation_quaternion = Quaternion((0, 1, 0), math.radians(best_deg))
        update()
        e = world_head("DEF-forearm.%s" % side)
        print("<<< elbow.%s roll %+.1f deg -> (%.3f, %.3f, %.3f)  [swept x %.3f..%.3f]"
              % (side, best_deg, e.x, e.y, e.z, min(lo), max(lo)))


# --- the idle itself ------------------------------------------------------------------------

def wave(phase, cycles, offset=0.0, lag=0.0):
    """One oscillator. `cycles` is whole cycles per loop, which is what makes the loop seamless --
    at phase 1.0 every layer is exactly back where it started."""
    return math.sin(2.0 * math.pi * (cycles * (phase - lag) + offset))


def pose_at(phase):
    """The idle, layered over the rest pose already in the bones.

    Every amplitude here is small on purpose. What sells a standing idle is not how far anything
    travels but that the parts disagree slightly about when to travel: the sway drives the hips,
    the chest answers it late and in the opposite direction, and the head answers the chest later
    still, so the spine reads as a chain being dragged rather than a rod being rocked.
    """
    # 1. The weight shift. One slow cycle over the whole loop, hips leading.
    sway = wave(phase, 1)
    sway_chest = wave(phase, 1, lag=0.055)
    sway_head = wave(phase, 1, lag=0.10)

    # 2. Breathing, three cycles, deliberately unrelated to the sway so the two drift in and out.
    breath = wave(phase, 3, offset=0.15)
    breath_late = wave(phase, 3, offset=0.15, lag=0.03)

    # 3. A slow fore/aft settle at two cycles, which keeps the sway from reading as a flat
    #    side-to-side line.
    drift = wave(phase, 2, offset=0.37)

    # 4. Head life, on its own periods entirely.
    head_yaw = wave(phase, 1, offset=0.28)
    head_pitch = wave(phase, 2, offset=0.61)
    head_tilt = wave(phase, 1, offset=0.72)

    torso = rig.pose.bones["torso"]
    torso.location = (sway * 0.011, drift * 0.005, -abs(sway) * 0.004 + breath * 0.0012)

    rot("hips", y=sway * 1.5, x=drift * 0.6)
    rot("chest", y=-sway_chest * 0.9, x=breath * 1.15 + drift * 0.4)
    # The tweak bones carry the lag down the spine; without them the torso turns as one piece.
    rot("tweak_spine.002", y=-sway_chest * 0.35, x=breath_late * 0.30)
    rot("tweak_spine.003", y=-sway_chest * 0.30, x=breath_late * 0.35)
    rot("tweak_spine.004", y=-sway_head * 0.25, x=breath_late * 0.20)

    rot("neck", y=-sway_head * 0.5, x=head_pitch * 0.5)
    rot("head", y=-sway_head * 0.7 + head_yaw * 1.6, x=head_pitch * 1.1, z=head_tilt * 1.3)

    # 5. Breathing shows in the shoulders and collarbones, not just the ribcage.
    for side, sign in (("L", 1.0), ("R", -1.0)):
        rot("shoulder.%s" % side, x=-breath_late * 0.7, z=-sign * breath_late * 0.5)

    # 6. The hands. They ride the sway, but LATE -- the lag is the whole point. Hands that track
    #    the torso exactly look welded to it; hands that arrive a fraction of a beat behind read as
    #    weight being carried. The breath lifts them very slightly as the chest rises.
    arm_lag = wave(phase, 1, lag=0.085)
    arm_drift = wave(phase, 2, offset=0.37, lag=0.06)
    arm_breath = wave(phase, 3, offset=0.15, lag=0.05)
    offset = Vector((arm_lag * 0.0115,
                     arm_drift * 0.0045,
                     arm_breath * 0.0035 - abs(arm_lag) * 0.0030))
    # The single re-evaluation of the frame: the spine is written above, and the shoulder joints
    # the arms hang off cannot be read until it has been applied.
    update()
    for side, base, sign in (("L", WRIST_L, 1.0), ("R", WRIST_R, -1.0)):
        wrist = base + offset
        # Fingers point inward, forward and down -- the direction a resting hand actually takes.
        aim_dir = Vector((-0.60 * sign, -0.42, -0.68))
        place("hand_ik.%s" % side, wrist, aim_dir, (0.0, -1.0, 0.25))
        # Elbow direction. The pole target is inert on this rig -- `pole_vector` is 0 in the limb's
        # own properties, which is Rigify's rotation-based mode, so the elbow swings with the IK
        # parent's roll and the pole bone is decoration. The angle was solved against the mesh at
        # build time; the small extra term is the elbow drifting with the sway.
        pbik = rig.pose.bones["upper_arm_ik.%s" % side]
        pbik.rotation_mode = 'QUATERNION'
        pbik.rotation_quaternion = Quaternion(
            (0, 1, 0), math.radians(ELBOW_ROT.get(side, 0.0) + arm_lag * 1.6 * sign))

    # 7. Fingers. Tiny, and each finger on its own phase so they never move as a block.
    for side, sign in (("L", 1.0), ("R", -1.0)):
        for i, finger in enumerate(("index", "middle", "ring", "pinky")):
            f = wave(phase, 2, offset=0.11 * i + (0.5 if side == "R" else 0.0))
            for seg, gain in ((1, 1.0), (2, 0.8), (3, 0.6)):
                name = "f_%s.0%d.%s" % (finger, seg, side)
                if name in rig.pose.bones:
                    rot(name, x=f * 1.5 * gain)
        t = wave(phase, 2, offset=0.42 + (0.5 if side == "R" else 0.0))
        for seg in (1, 2, 3):
            name = "thumb.0%d.%s" % (seg, side)
            if name in rig.pose.bones:
                rot(name, x=t * 1.1)

    # 8. Blinks. Driven by bones rather than the shape keys the FBX uses, because .pskn carries
    #    only bone matrices -- keyed as shape keys they simply would not reach the device, and a
    #    character that never blinks reads as dead the moment you notice it.
    apply_lids(blink_amount(phase * LOOP))

    # 9. The legs take the weight shift, so the sway comes from her standing rather than from her
    #    being pushed.
    for side, sign in (("L", 1.0), ("R", -1.0)):
        rot("thigh_fk.%s" % side, y=sway * 0.5, x=-abs(sway) * 0.35 * (1.0 + sign * 0.3))
        rot("shin_fk.%s" % side, x=abs(sway) * 0.45)

    update()


ANIMATED = (
    ["torso", "hips", "chest", "neck", "head",
     "tweak_spine.002", "tweak_spine.003", "tweak_spine.004"]
    + ["shoulder.%s" % s for s in "LR"]
    + ["hand_ik.%s" % s for s in "LR"]
    + ["upper_arm_ik.%s" % s for s in "LR"]
    + ["thigh_fk.%s" % s for s in "LR"]
    + ["shin_fk.%s" % s for s in "LR"]
    + ["f_%s.0%d.%s" % (f, s, side)
       for f in ("index", "middle", "ring", "pinky") for s in (1, 2, 3) for side in "LR"]
    + ["thumb.0%d.%s" % (s, side) for s in (1, 2, 3) for side in "LR"]
)


FACE_ANIMATED = (
    ["lid.T.%s%s" % (s, suffix) for s in "LR" for suffix in ("", ".001", ".002", ".003")]
    + ["lid.B.%s%s" % (s, suffix) for s in "LR" for suffix in ("", ".001", ".002", ".003")]
    + ["eyes", "lips.L", "lips.R"]
)


def bake():
    scene.render.fps = FPS
    scene.frame_start = 1
    scene.frame_end = TOTAL

    # THE ACTION HAS TO EXIST FIRST. Any keyframe_insert before this auto-creates an action of
    # its own, which assigning ours then throws away -- taking the IK/FK switch keys with it.
    rig.animation_data_create()
    action = bpy.data.actions.new("Aether_Idle")
    rig.animation_data.action = action

    rest = {}
    build_rest_pose()
    solve_lids()
    for pb in rig.pose.bones:
        rest[pb.name] = (pb.location.copy(), pb.rotation_quaternion.copy())


    # Frames 1..INTRO are the arrival; INTRO+1..TOTAL are the loop itself, baked at phases 0..359
    # exactly as before. The intro runs the SAME phases 0..INTRO-1 with a decaying offset on top, so
    # its last frame lands on the loop's phase INTRO-1 and playback continues into phase INTRO with
    # nothing to blend. See intro_pose.
    for frame in range(1, TOTAL + 1):
        scene.frame_set(frame)
        for name, (loc, quat) in rest.items():
            pb = rig.pose.bones[name]
            pb.location = loc
            pb.rotation_mode = 'QUATERNION'
            pb.rotation_quaternion = quat
        if frame <= INTRO:
            phase = (frame - 1) / float(LOOP)
            pose_at(phase)
            intro_pose((frame - 1) / float(INTRO - 1))
        else:
            # Frame LOOP+1 would be frame 1 again, so the last baked frame stops just short of it
            # and the loop closes without a duplicated pose.
            pose_at((frame - INTRO - 1) / float(LOOP))
        for name in ANIMATED + FACE_ANIMATED:
            pb = rig.pose.bones.get(name)
            if not pb:
                continue
            pb.keyframe_insert("location", frame=frame, group=name)
            pb.keyframe_insert("rotation_quaternion", frame=frame, group=name)

    print("<<< baked %d frames (%d intro + %d loop) over %d bones"
          % (TOTAL, INTRO, LOOP, len(ANIMATED) + len(FACE_ANIMATED)))


def blinks():
    """SUPERSEDED, and deliberately not called any more.

    Blinks are driven by the lid BONES now (see apply_lids), for the plain reason that .pskn carries
    only bone matrices -- keyed as shape keys they never reached the device at all, and Aether did
    not blink in Prism. Keying both mechanisms at the same frames would close her eyes twice over in
    the FBX, so this is left here only as the record of what it used to do.
    """
    body = bpy.data.objects["Body"]
    keys = body.data.shape_keys
    if not keys:
        return
    pairs = [k for k in ("Eye_Blink_L", "Eye_Blink_R") if k in keys.key_blocks]
    if not pairs:
        return
    for k in pairs:
        keys.key_blocks[k].value = 0.0
        keys.key_blocks[k].keyframe_insert("value", frame=1)
    # A real blink is fast down, a beat closed, slower up -- roughly 7 frames at 30fps.
    for start in (47, 143, 191, 296):
        for k in pairs:
            kb = keys.key_blocks[k]
            for offset, value in ((0, 0.0), (3, 1.0), (4, 1.0), (9, 0.0)):
                kb.value = value
                kb.keyframe_insert("value", frame=start + offset)
        kb_end = start + 9
    for k in pairs:
        keys.key_blocks[k].value = 0.0
        keys.key_blocks[k].keyframe_insert("value", frame=LOOP)
    print("<<< blinks keyed")


# --- preview + export -------------------------------------------------------------------------

def setup_preview():
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.render.resolution_x = 640
    scene.render.resolution_y = 520
    shading = scene.display.shading
    shading.light = 'STUDIO'
    shading.color_type = 'TEXTURE'
    shading.show_cavity = True
    scene.display.render_aa = '8'
    for o in bpy.data.objects:
        if o.type == 'MESH' and o.name in ("blender file_Collider", "EyeOcclusion", "TearLine"):
            o.hide_render = True
        # Forced on rather than left to whatever the .blend had: pose checks are clothed. The FBX
        # export selects its own object list and does not include these.
        if o.type == 'MESH' and o.name in PREVIEW_ONLY:
            o.hide_render = False
            o.hide_viewport = False
        if o.type in ('ARMATURE', 'EMPTY'):
            o.hide_render = True
    cam_data = bpy.data.cameras.new("PreviewCam")
    cam_data.lens = 70
    cam = bpy.data.objects.new("PreviewCam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    return cam


def shoot(cam, name, location, look_at):
    cam.location = location
    d = Vector(look_at) - Vector(location)
    cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    scene.render.filepath = os.path.join(OUT_DIR, name)
    bpy.ops.render.render(write_still=True)
    print("<<< RENDERED", name)


def main():
    clean()
    if POSE_ONLY:
        build_rest_pose()
        solve_lids()
        pose_at(0.0)
        update()
    else:
        bake()

    print("<<< CHECK hand positions at frame 1")
    scene.frame_set(1)
    update()
    for side in "LR":
        p = world_head("hand_fk.%s" % side)
        print("<<<   wrist.%s (%.3f, %.3f, %.3f)" % (side, p.x, p.y, p.z))
    gap = (world_head("hand_fk.L") - world_head("hand_fk.R")).length
    print("<<<   wrist separation %.3f m" % gap)
    for side in "LR":
        p = world_head("forearm_fk.%s" % side)
        print("<<<   elbow.%s (%.3f, %.3f, %.3f)" % (side, p.x, p.y, p.z))
    # The FK controls are not the mesh. These are, so this is the check that matters.
    print("<<< CHECK deform chain follows")
    for name in ("DEF-upper_arm.L", "DEF-forearm.L", "DEF-hand.L",
                 "DEF-upper_arm.R", "DEF-forearm.R", "DEF-hand.R"):
        if name in rig.pose.bones:
            p = world_head(name)
            print("<<<   %-18s (%.3f, %.3f, %.3f)" % (name, p.x, p.y, p.z))
    # Interpenetration check. Bone heads are not the skin, but two finger joints closer than about
    # a centimetre and a half are almost certainly inside each other's geometry.
    left, right = [], []
    for finger in ("index", "middle", "ring", "pinky", "thumb"):
        for seg in (1, 2, 3):
            for side, bucket in (("L", left), ("R", right)):
                nm = ("DEF-thumb.0%d.%s" if finger == "thumb" else "DEF-f_" + finger + ".0%d.%s")
                nm = nm % (seg, side)
                if nm in rig.pose.bones:
                    bucket.append((nm, world_head(nm).copy()))
    worst = None
    for ln, lp in left:
        for rn, rp in right:
            d = (lp - rp).length
            if worst is None or d < worst[0]:
                worst = (d, ln, rn)
    if worst:
        print("<<<   closest finger joints %.1f mm  (%s <-> %s)"
              % (worst[0] * 1000, worst[1], worst[2]))

    # WHAT THE MOTION ACTUALLY MEASURES. Sampling the deform bones across the loop is a better
    # check than looking at stills: it says how far each part travels in centimetres, which is the
    # thing that decides whether an idle reads as alive or as a statue with a twitch.
    if not POSE_ONLY:
        print("<<< MOTION over the loop (deform bones, cm)")
        tracked = ("DEF-spine.006", "DEF-hand.L", "DEF-hand.R", "DEF-upper_arm.L", "DEF-thigh.L")
        samples = {n: [] for n in tracked}
        for frame in range(1, LOOP + 1, 5):
            scene.frame_set(frame)
            update()
            for n in tracked:
                if n in rig.pose.bones:
                    samples[n].append(world_head(n).copy())
        for n, pts in samples.items():
            if not pts:
                continue
            rng = [max(p[i] for p in pts) - min(p[i] for p in pts) for i in range(3)]
            print("<<<   %-18s x %.2f  y %.2f  z %.2f" % (n, rng[0] * 100, rng[1] * 100, rng[2] * 100))

        # The loop closes only if the last frame sits one step short of the first, not on top of it
        # and not a jump away.
        scene.frame_set(LOOP); update()
        last = world_head("DEF-spine.006").copy()
        scene.frame_set(1); update()
        first = world_head("DEF-spine.006").copy()
        scene.frame_set(2); update()
        second = world_head("DEF-spine.006").copy()
        print("<<<   loop seam: |f360-f1| = %.4f cm, one step |f2-f1| = %.4f cm"
              % ((last - first).length * 100, (second - first).length * 100))

    # Only a tight crop on the hands from here. The pose is settled and verified numerically; there
    # is no reason to keep rendering the whole figure.
    cam = setup_preview()
    scene.frame_set(1)
    shoot(cam, "hands_f001.png", (-0.34, -0.92, 1.16), (0.0, -0.10, 1.03))
    scene.frame_set(180)
    shoot(cam, "hands_f180.png", (-0.34, -0.92, 1.16), (0.0, -0.10, 1.03))

    if DO_FBX:
        for o in bpy.data.objects:
            o.select_set(False)
        rig.select_set(True)
        for name in character_meshes():
            bpy.data.objects[name].select_set(True)
        bpy.context.view_layer.objects.active = rig
        path = os.path.join(OUT_DIR, "Aether_Idle.fbx")
        bpy.ops.export_scene.fbx(
            filepath=path,
            use_selection=True,
            object_types={'ARMATURE', 'MESH'},
            # Only the DEF- bones survive. The control rig is 724 bones of IK targets, mechanism
            # and widgets that mean nothing outside Blender; the 179 that actually deform her are
            # what any engine needs.
            use_armature_deform_only=True,
            add_leaf_bones=False,
            bake_anim=True,
            bake_anim_use_all_bones=True,
            bake_anim_use_nla_strips=False,
            bake_anim_use_all_actions=False,
            bake_anim_force_startend_keying=True,
            bake_anim_step=1.0,
            # No curve simplification. The motion is a sum of oscillators sampled per frame, and
            # letting the exporter thin the keys is exactly how a smooth idle turns into a
            # stepped one.
            bake_anim_simplify_factor=0.0,
            mesh_smooth_type='FACE',
            path_mode='COPY',
            embed_textures=False,
            axis_forward='-Z',
            axis_up='Y',
        )
        print("<<< FBX", path, os.path.getsize(path), "bytes")
    print("<<< DONE")


# Guarded so the skinned exporter can import this module and reuse the rig setup, the rest pose
# and the oscillator stack without also triggering a bake and a render.
if __name__ == "__main__":
    main()
