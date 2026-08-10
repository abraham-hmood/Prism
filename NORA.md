# Nora

A locally-runnable image and video generator whose architecture is derived, mechanism by
mechanism, from primate visual cortex. She lives in Prism's Messages page alongside Sam, and
she trains and generates entirely on the phone.

Nora is not a diffusion model with anatomical variable names. There is no U-Net, no noise
schedule, no classifier with a decoder bolted on, and no backpropagation anywhere in her.
Generation is mental imagery: the same predictive-coding hierarchy that explains sensory input,
run with a concept clamped at the top and sensory error unclamped at the bottom.

---

## 1. The three questions that prompt says to ask first

**Hardware.** Snapdragon 8 Gen 1, ~8 GB shared RAM, of which an Android app realistically gets a
few hundred megabytes of heap. No CUDA. No GPU compute path. No PyTorch, no TensorFlow, no
autodiff framework of any kind. Kotlin/JVM.

This single constraint rewrites the whole brief. The prompt asked for a PyTorch implementation;
PyTorch does not exist on this target and a Bazel-scale C++ dependency tree is not going to
either. What Nora is instead: plain Kotlin, `FloatArray` throughout, hand-written convolutions,
a fixed worker pool for channel-parallel loops.

**Priority.** Both, with video as the goal and images as the substrate it is built from.

**Fidelity versus quality.** Fidelity, decisively. Nora will not match a diffusion model on
sample quality and this document does not pretend otherwise — Section 7 states the gap
numerically where it can be estimated.

---

## 2. Why biological fidelity is an engineering asset here, and where it is a tax

This is the part worth reading even if you skip the rest.

### Where the two goals genuinely align

**Local learning rules remove the need for an autodiff engine.** Rao & Ballard's (1999) update is

```
ΔW  ∝  (prediction error in the area below) × (activity in the area above)
```

Every term is available at the synapse. No backward pass, no retained activation graph, no
tape. This is the reason Nora can *train* on a phone in plain Kotlin. A diffusion U-Net would
have made an autodiff framework mandatory, and the project would have stopped there. See
[`PredictiveLink.learn`](app/src/main/java/com/prism/launcher/nora/PredictiveLink.kt).

**Most of early vision is analytically specified, so it costs zero training.** DoG
centre-surround, Gabor banks, the Adelson-Bergen energy model, motion energy, optic-flow
templates — these are all closed-form. Roughly the first half of the pipeline needs no data at
all. A conventional generator has to learn edge detection from scratch; Nora is born with it,
the way an animal is.

**Log-polar foveation is a genuine memory strategy.** 32×48 = 1,536 sampled locations cover a
128×128 canvas with foveal detail, against 16,384 for a dense grid. And because a fixed-size
filter on the log-polar sheet *is* an eccentricity-scaled filter in the visual field, one Gabor
bank does the work of a per-eccentricity family.

**Saccadic refinement decouples output resolution from peak memory.** Nora never materializes a
whole image. She takes fixations, each a small high-resolution sample, and integrates them into
a canvas. Bigger output means more fixations, not a bigger tensor. This is the main lever, and
it exists because foveated vision works this way.

**Local receptive fields with shared kernels keep the parameter count at ~150k** — four orders
of magnitude below a small diffusion model. Cortical connections really are local and
topographic; copying that is what makes the connectome a few hundred kilobytes on disk.

### Where fidelity costs quality

**No compositional text encoder.** The semantic hub is a hashed bag of words. "A dog chasing a
cat" and "a cat chasing a dog" produce identical patterns. A diffusion model with a CLIP or T5
encoder handles this trivially. This is Nora's single largest capability gap, and it is a
direct cost of not bolting on a transformer.

**Complex-cell energy discards phase.** That is what a complex cell is *for*, and it is why V1
is robust. It also means V1 cannot analytically project its own activity back into an image;
the generative path has to go through the learned link instead.

**Iterative inference is slow.** Predictive coding settles over 12–20 iterations, each a full
descend-and-ascend pass. A feedforward network is one pass. This is a real constant-factor cost
of the recurrent architecture.

**Sparse codes throw away information.** IT runs at ~6% active. That is measured biology
(Rolls & Tovee 1995) and it is good for interference and metabolism. It is not good for
reconstruction fidelity.

---

## 3. Region → module map

| Brain area | Computation implemented | Laminar / anatomical detail | Code module | Key citations |
|---|---|---|---|---|
| **Retina** | Log-polar foveated sampling with cortical magnification M(e)=A/(e+e₂); DoG ON/OFF; M/P/K split; contrast gain control | Ganglion cell centre-surround; ON and OFF as separate rectified populations | [`Retina.kt`](app/src/main/java/com/prism/launcher/nora/Retina.kt) | Rodieck 1965; Enroth-Cugell & Robson 1966; Daniel & Whitteridge 1961; Schwartz 1977, 1980; Shapley & Victor 1978; Derrington, Krauskopf & Lennie 1984 |
| **LGN / pulvinar** | Gain and precision control, not relay; M/P/K-separated normalization pools; error-driven spatial precision | Magno laminae 1–2, parvo 3–6, konio interlaminar; ~90% of input is cortical feedback | [`Lgn.kt`](app/src/main/java/com/prism/launcher/nora/Lgn.kt) | Sherman & Guillery 2002; Saalmann & Kastner 2011; Kanai et al. 2015 |
| **V1** | Overcomplete Gabor bank (8 orientations × 3 SFs × 2 phases); simple→complex via energy model; pinwheel orientation map as a real gain constraint | L4C input; quadrature pairs collapsed to complex cells | [`V1.kt`](app/src/main/java/com/prism/launcher/nora/V1.kt), [`OrientationMap.kt`](app/src/main/java/com/prism/launcher/nora/OrientationMap.kt) | Marcelja 1980; Daugman 1985; Jones & Palmer 1987; Adelson & Bergen 1985; Ringach 2002; Blasdel & Salama 1986; Bonhoeffer & Grinvald 1991; Ohki et al. 2006; Rojer & Schwartz 1990 |
| **Divisive normalization** | R = x^n / (σ^n + Σ w·x^n) at **every** stage | Cross-orientation, cross-space, and cross-feature pools per area | [`Normalization.kt`](app/src/main/java/com/prism/launcher/nora/Normalization.kt) | Heeger 1992; Carandini, Heeger & Movshon 1997; Carandini & Heeger 2012; Morrone, Burr & Maffei 1982 |
| **V2 / V3** | Border ownership; illusory contours via collinear facilitation; Portilla-Simoncelli texture statistics | Owned-side surround asymmetry; association-field lateral kernels | [`V2.kt`](app/src/main/java/com/prism/launcher/nora/V2.kt) | Zhou, Friedman & von der Heydt 2000; von der Heydt, Peterhans & Baumgartner 1984; Field, Hayes & Hess 1993; Portilla & Simoncelli 2000; Freeman et al. 2013; Ziemba et al. 2016 |
| **V4** | Curvature × angular-position shape-part tuning referenced to object centroid; von Kries colour constancy; boundary-gated surface fill-in | Fill-in conductance gated by V2 border ownership | [`V4.kt`](app/src/main/java/com/prism/launcher/nora/V4.kt) | Pasupathy & Connor 1999, 2001, 2002; Zeki 1983; von Kries 1902; Foster 2011; Grossberg & Mingolla 1985; Paradiso & Nakayama 1991; Komatsu 2006 |
| **IT** | View-tolerant identity; sparse distributed code at ~6% active; topographic sheet with emergent category patches | k-WTA as fast pooled inhibition; SOM-style topographic update | [`It.kt`](app/src/main/java/com/prism/launcher/nora/It.kt) | Rolls & Tovee 1995; Young & Yamane 1992; Logothetis & Sheinberg 1996; DiCarlo & Cox 2007; Kanwisher et al. 1997; Tsao et al. 2006; Arcaro & Livingstone 2017 |
| **MT / V5** | Spatiotemporal motion energy; direction × speed tuning; motion opponency; component→pattern pooling | Two-stage: linear V1 pooling → squaring → normalization | [`Mt.kt`](app/src/main/java/com/prism/launcher/nora/Mt.kt) | Adelson & Bergen 1985; Movshon et al. 1985; Snowden et al. 1991; Rust et al. 2006 |
| **MST** | Optic-flow templates (expansion, contraction, rotation, 8 translations); self-motion parameterization driving inter-frame warp | Whole-field pattern tuning, not local translation | [`Mst.kt`](app/src/main/java/com/prism/launcher/nora/Mst.kt) | Saito et al. 1986; Duffy & Wurtz 1991; Graziano, Andersen & Snowden 1994 |
| **Canonical microcircuit** | L4 input; L2/3 error, ascending; L5/6 representation, descending as prediction; L6→thalamus gain loop | Feedback avoids L4; hierarchy defined by laminar termination pattern | [`CorticalRegion.kt`](app/src/main/java/com/prism/launcher/nora/CorticalRegion.kt), [`PredictiveLink.kt`](app/src/main/java/com/prism/launcher/nora/PredictiveLink.kt) | Rao & Ballard 1999; Douglas & Martin 1991, 2004; Felleman & Van Essen 1991; Bastos et al. 2012, 2015; Michalareas et al. 2016 |
| **Neuromodulation** | ACh = expected uncertainty, gain on error units; NE = unexpected uncertainty, network reset; DA = learning-rate modulation; attention = precision weighting | Scalar per transmitter | [`Neuromodulators.kt`](app/src/main/java/com/prism/launcher/nora/Neuromodulators.kt) | Yu & Dayan 2005; Bouret & Sara 2005; Hasselmo & McGaughy 2004; Schultz, Dayan & Montague 1997; Feldman & Friston 2010 |
| **Hippocampus / EC** | DG pattern separation; CA3 recurrent autoassociator for completion; grid code for layout; reward-weighted interleaved replay | DG ~4% sparse, CA3 ~10%; 3 grid modules, scale ratio ~1.42 | [`Hippocampus.kt`](app/src/main/java/com/prism/launcher/nora/Hippocampus.kt) | Marr 1971; Hopfield 1982; Treves & Rolls 1994; O'Reilly & McClelland 1994; Leutgeb et al. 2007; Nakazawa et al. 2002; Hafting et al. 2005; Stensola et al. 2012; Wilson & McNaughton 1994; McClelland, McNaughton & O'Reilly 1995; Ambrose, Pfeiffer & Foster 2016 |
| **Anterior temporal lobe** | Amodal semantic hub binding words ↔ visual patterns, Hebbian/Oja | Hub-and-spoke | [`SemanticHub.kt`](app/src/main/java/com/prism/launcher/nora/SemanticHub.kt) | Patterson, Nestor & Rogers 2007; Lambon Ralph et al. 2017; Hebb 1949; Oja 1982 |
| **Imagery / generation** | Clamp IT, unclamp sensory error, run hierarchy top-down; saccadic refinement; trans-saccadic integration | Same machinery as perception, no separate decoder | [`MentalImagery.kt`](app/src/main/java/com/prism/launcher/nora/MentalImagery.kt), [`NoraBrain.imagine`](app/src/main/java/com/prism/launcher/nora/NoraBrain.kt) | Kosslyn et al. 1995; Naselaris et al. 2015; Dijkstra, Bosch & van Gerven 2019; Pearson 2019; Friston et al. 2012; Najemnik & Geisler 2005; Melcher & Colby 2008 |
| **Sleep / consolidation** | Interleaved replay into cortical weights; synaptic downscaling and pruning | Every third epoch | [`NoraTrainer.sleepPhase`](app/src/main/java/com/prism/launcher/nora/NoraTrainer.kt) | Tononi & Cirelli 2003, 2014; McClelland et al. 1995 |

---

## 4. Honesty ledger — analogy versus mechanism

Graded. **[M]** = mechanism as described in the literature. **[S]** = right mechanism,
simplified. **[A]** = right outcome, wrong route. **[E]** = engineering, no biological claim.

| Component | Grade | What's actually true |
|---|---|---|
| Log-polar cortical magnification | **M** | Schwartz's mapping, implemented directly. Rotation/scale-tolerance consequence is real. |
| DoG centre-surround, ON/OFF split | **M** | Standard model, standard anatomy. |
| Divisive normalization | **M** | The canonical computation, applied where the literature says it applies. |
| Gabor simple cells, energy-model complex cells | **M** | Measured receptive fields; Adelson-Bergen energy. |
| MT motion energy, opponency, pattern cells | **M** | Adelson-Bergen plus Rust et al.'s two-stage structure. |
| MST flow templates | **M** | Duffy & Wurtz tuning, reproduced. |
| M/P/K split | **S** | Real division of labour, but magno's biphasic impulse response is reduced to a first-order temporal derivative. |
| Retinal contrast gain control | **S** | Naka-Rushton with pooled local contrast; real gain control has multiple timescales. |
| Pinwheel orientation map | **A** | Right statistics (pinwheel density, linear zones) by band-passed-noise construction. Real maps develop by activity-dependent self-organization; Nora installs the endpoint. |
| Border ownership | **A** | Zhou et al.'s selectivity, produced by a hand-designed asymmetric surround. The real circuit is unknown — recurrent grouping cells (Craft et al. 2007) versus fast horizontal propagation is an open question. |
| Portilla-Simoncelli texture stats | **S** | 8 pooled correlation features versus ~700 in the full model. Keeps the cross-orientation/cross-scale component Freeman et al. identified as V2-discriminating; drops marginal and phase statistics. |
| V4 curvature basis | **A** | Pasupathy-Connor tuning functions installed directly rather than learned. Whether V4 arrives at this basis by experience or by input statistics is unsettled. |
| IT sparse code | **M** | Population sparseness in the measured range, enforced by pooled inhibition. |
| IT category patches | **A** | **The clearest analogy in the ventral stream.** A Kohonen SOM is an algorithm, not a mechanism. Biology has proto-organization present in infancy that experience elaborates (Arcaro & Livingstone 2017), driven by retinotopic biases and connectivity — not by a shrinking-neighbourhood learning rule. |
| Laminar predictive coding | **S**, contested | See Section 5. |
| ACh / NE / DA | **S** | Yu & Dayan's computational level only. One scalar per transmitter caricatures systems with differentiated projections, opposing receptor subtypes, and timescales from milliseconds to hours. |
| Pulvinar precision routing | **A** | The pulvinar's role is genuinely unsettled; error-driven spatial precision is one computational reading, not a settled mechanism. |
| DG / CA3 | **M** | Marr-Treves-Rolls division of labour, implemented as described. |
| Grid cells for camera pose | **E** | **No biological claim whatsoever.** Grid cells code the animal's own position. Nothing says they parameterize viewpoint for imagery. The periodic multi-scale code is useful for compact 2-D layout; that is the entire reason it is here. |
| Replay consolidation | **S** | Algorithm-level CLS, which is legitimate at that level. Not a model of sharp-wave ripples. |
| Synaptic permanence | **A** | Loosely stands in for structural stabilization of strong pathways. Not myelination in any real sense. |
| Retinal surface (DC) channel | **E** | Real ganglion cells are band-pass and lose DC — that loss is why the Cornsweet illusion works, and brightness is recovered by filling-in. Nora carries an explicit low-pass surface channel so generation has a defined DC. V4 fill-in is still implemented and still runs. |
| Dorsal stream as warp-only | **S** | Real MT is reciprocally connected with V1/V2/V4/MST and sends substantial feedback. Nora captures the functional consequence for video without the reciprocal machinery. |
| Semantic hub | **S** | Hub-and-spoke convergence is right. The lexical front end has no syntax and no compositionality. |

### Genuinely unsettled neuroscience, flagged rather than papered over

- **Whether cortex implements predictive coding at all.** Substantive critiques exist: explicit
  subtractive error units have not been unambiguously identified, and the laminar/spectral data
  admit other readings (Kogo & Trengove 2015; Heilbron & Chait 2018; Walsh et al. 2020).
- **Whether feedback is subtractive.** Classic predictive coding says yes. Alternatives propose
  multiplicative gain or a different functional role entirely.
- **How border ownership is computed.** Open.
- **Whether IT category patches are innate, experience-driven, or a proto-map elaborated by
  experience.** Actively contested.
- **What the pulvinar does.** Attention, routing, and confidence signalling are all live
  proposals.

---

## 5. The predictive-coding bet, stated plainly

Nora commits to the strong version of predictive coding: feedforward connections carry
prediction error, feedback carries predictions, and inference is iterative error minimization.

This is a **bet, not a fact.** It has real support — laminar recordings find feedforward gamma
and feedback beta with the predicted profiles (Bastos et al. 2015; Michalareas et al. 2016) —
and real critics. Nora takes it because it is implementable with local rules and because it
makes perception and generation the *same machinery*, which is the architectural claim the
whole project rests on.

If predictive coding is wrong about cortex, Nora is still a working generative model. She just
would not be a model of how you see.

---

## 6. Video: why this doesn't flicker the way frame-wise diffusion does

A frame-wise diffusion video model samples each frame from noise conditioned on text and maybe
the previous frame. Everything the conditioning does not pin down gets re-decided every frame.
That is where flicker and identity drift come from — the model re-deciding facts it already
decided.

Nora never re-decides:

1. **The concept stays clamped in IT for the entire clip.** Object identity is a constant of the
   generation, not a per-frame sample.
2. **Each frame starts from the previous frame's cortical representation**, transported forward
   by the flow field MST synthesized. The prior for frame *t+1* is literally frame *t*, moved.
3. **Predictive coding then explains only the residual** — what the warp got wrong. Content that
   is not moving has nothing to explain, and therefore does not change.

**The cost, stated honestly:** because the whole clip is one continuous settling of one held
concept, Nora *cannot* produce a cut, a new object entering frame, or any genuine change of
scene content. She produces a continuously-transformed view of a single concept. That is a real
limitation and it comes directly from the mechanism that buys the stability.

---

## 6a. A failure worth recording

The first trained connectomes were worthless, and the way they failed is instructive enough to
keep in the documentation rather than quietly fix.

`PredictiveLink.learn` accumulated the Hebbian product over every position on the cortical sheet
and applied `lr * sum` as one weight update — without dividing by the number of terms. The
fan-in initialization (`1/sqrt(botC · k²)`) correctly scales `predict` and `propagateError`,
because those sum over exactly the axes it accounts for. `learn` sums over a *different* axis —
cortical space, `topH × topW` — which the initialization never compensated for. On the
V1→retina link that is 1536 terms, and since early predictions sit near zero the errors are
systematically same-signed rather than cancelling. A nominal learning rate of 0.006 became an
effective rate near 9. The weights left float range within a handful of images.

What made it expensive was not the divergence but the *silence*. `Normalization.metabolicTax`
read `if (v > tax) … else if (v < -tax) … else 0f`, and since every comparison against NaN is
false, a NaN fell through to the zero branch. IT is the only layer that runs a tax, so IT is
where NaN got laundered into clean zeros. An all-zero IT then made `SemanticHub.bind` hit
`if (target == 0f) continue` on every iteration, so the hub learned nothing while reporting
success. The visible result was a black image and a polite "I haven't seen that" — two full
training runs producing plausible output from a dead network. The only symptom was `NaN` in the
error column, which reads like a display quirk.

Fixes, all in this commit: the update averages instead of summing, with a per-step clamp at a
quarter of the initialization scale and a hard weight bound; the tax is now a fraction of the
k-WTA threshold rather than an absolute constant, so it cannot empty a layer; k-WTA runs first
because pooled inhibition is what sets a threshold relative to current population activity;
non-finite values are reported through `NoraHealth` instead of absorbed; training aborts on
divergence and refuses to checkpoint a diverged brain over a good one; and the persistence
format is at version 2 so every version-1 file is rejected on sight.

### The second one, which the first was hiding

Fixing the weights exposed a separate runaway in the *activations*. It had probably always been
there; the weights simply used to go NaN faster.

`updateRepresentation` is a gradient step on `‖input − W·representation‖²`, because `predict`
and `propagateError` share the same weights as transposes. That is correct predictive coding,
but a gradient step is only stable while `PC_RATE × λmax(WᵀW) < 2`, and a network with no
normalization layers has nothing enforcing that bound.

At initialization it holds comfortably: fan-in init puts the V1→retina operator norm near 1.4,
so `0.28 × 2.5 ≈ 0.7`. Learning breaks it. Early in training every prediction starts near zero,
so prediction errors are systematically same-signed and every synapse is pushed the same way at
once — magnitude grows roughly linearly rather than settling. By the fifth training image the
weights were around five times their initial scale, λmax up ~25× to roughly 46, and
`0.28 × 46 ≈ 13`. Past that boundary each of the twelve inference iterations in a settle
multiplies the representation by about three, so a single settle amplifies by ~5 × 10⁵.

The error then overflowed float, the neuromodulator's running variance computed
`Infinity − Infinity`, and dopamine became NaN. Because acetylcholine multiplies into every
region's error computation and dopamine multiplies into every learning rate, one bad scalar
poisoned the whole network in a single step.

Three structural fixes, in order of how much they matter:

- **Representations are compressively saturated** at a maximum firing rate. `rectify()` bounded
  activity from below only, leaving an unbounded quantity inside a recurrent loop. Real neurons
  have a ceiling; modelling it converts catastrophic divergence into recoverable saturation.
- **Each link's weight RMS is capped** at 2.5× its initialization value, rescaling after every
  update. This directly bounds the operator norm, which is the quantity that was drifting.
  Rescaling preserves the pattern the learning rule chose and discards only the overall gain,
  which carries no information. The per-synapse ceiling came down from 25× to 3× — 25× permitted
  a 625× growth in λmax and guaranteed eventual instability.
- **The neuromodulators consume per-unit RMS error**, not summed energy. Squaring a number in
  the thousands to track its variance overflows on the first spike; on an RMS scale the same
  statistics live near 0.2 and cannot. Every modulator is now bounded and NaN-checked after
  assignment, with a neutral fallback, because `coerceIn` passes NaN straight through.

The error readout is now RMS too. The old one summed squares over ~70,000 units, so it read in
the thousands even when every unit was off by a healthy 0.2 — it could not distinguish
converging from diverging, which made it useless as exactly the diagnostic it resembled.

`LEARN_RATE` is 0.005, but the point of the three fixes above is that stability no longer
depends on that number being lucky. It can be tuned up on evidence.

### The third one: generation that ignored the prompt

Different prompts produced pixel-identical grey images. Not an undertrained model — a bug with
a precise location.

`imagine` computed each area's top-down prediction and used it to form prediction errors, but
never assigned it to `topDown`. `updateRepresentation` pulls each region toward
`topDown`, so with that field holding a stale, effectively zero value, every region was being
pulled toward *silence* rather than toward what IT was asking for. The clamped concept therefore
shaped only the initial cascade. Twenty refinement iterations of a self-referential loop —
retina → analytic encoders → V2 → project back down → retina — then converged on a fixed point
determined entirely by the weights. After step one the prompt was no longer part of the
computation at all, which is why two unrelated prompts gave the same picture.

The imagery loop now mirrors `settle` exactly: predictions descend and are installed as priors,
errors ascend, and V1 is driven by retinal prediction error rather than being overwritten
wholesale by V2's projection. The prior strength is raised to 1.4 during imagery (against 0.5
during perception), because perception should let evidence win arguments with expectation and
imagery is the opposite case — there is no evidence, only the model re-reading its own output,
and the concept has to stay in charge of that conversation. Raising it is also unconditionally
stabilizing, since it is a contraction toward `topDown`.

Two smaller contributors to the same symptom:

- **Colour constancy was being applied to the output.** Von Kries adaptation discounts an
  illuminant in *input*; `perceive` already runs it on the sensory sample, which is correct.
  Running it again on generated output normalizes R, G and B toward a common mean and erases
  whatever chromatic difference the generative pathway produced. Removed from `finishSurface`.
- **Six dark dots, one per saccade.** The foveal splat radius floored at 0.75 px, so the
  innermost rings wrote sub-pixel islands that never blended with their neighbours — and since
  foveal confidence is near 1.0, each fixation stamped a hard dot at its own centre. The image
  was, quite literally, showing where she had looked. Floor raised to 1.4 px so foveal support
  always overlaps.

### The fourth one: the fix for the second one caused it

Black images again after a full ten-epoch run, in every imagery mode. Not overfitting — a
collapse to a constant, produced by the weight-spectrum constraint that was added to cure the
divergence above.

`constrainSpectrum` capped the RMS of an entire link and rescaled the whole array when it was
exceeded. That ceiling is reached inside the first epoch, and from then on the link runs under a
fixed norm **budget its output channels have to compete for**. On V1→retina the contrast channels
have large, consistent Hebbian products against rectified V1 energy; the surface channels carry
smooth low-variance DC and produce much smaller ones. So on every learning step the contrast
channels took a little more of the budget and the surface channels were rescaled toward zero —
monotonically, for as long as training continued. Losing them means losing all brightness. Two
epochs gave dim structured output; ten gave nothing.

Three separate things then conspired to hide it:

- **The contrast stretch guard fired backwards.** `if (hi - lo > 1e-4)` disabled the one
  mechanism guaranteeing a well-exposed image *precisely* when the image was degenerate. Any
  output with faint structure got stretched and looked fine; a collapsed one fell through
  unstretched and rendered as `#000000`. A safety that fails closed on its own failure mode
  hides the thing it was watching for.
- **Rectify-before-stretch.** Every channel was rectified before the range was measured, so the
  half of the signed prediction below zero was discarded before the stretch could use it.
- **Nothing was ever protected from pruning.** Permanence accrued only above `dopamine > 1.2`,
  and the trainer's curriculum boost was being destroyed before `learn()` could read it (below),
  so in an unfocused run permanence stayed at zero and every synapse was fully exposed to
  sleep-phase downscaling and pruning.

Fixes: the spectrum constraint is now **per output channel**, which removes the competition
entirely and is strictly tighter than the global bound it replaces (every channel bounded implies
the whole link bounded), so the stability guarantee is unaffected. Surface channels stay signed
until after the stretch. The stretch guard drops to `1e-6` and a genuinely flat result is
*reported* — `/status` shows surface-vs-contrast pathway gain and warns when they diverge, and a
blank generation says in the reply that it produced a constant rather than a picture. Sleep
pruning is withheld from any channel already at or below its initialization strength.

**Adjacent bug found while tracing it.** `Neuromodulators.update()` recomputed dopamine as
`1 + phasic`, wiping any level `setDopamine` had established. The trainer sets a raised level for
a focused curriculum item, then calls `perceive()` → `settle()` → `update()`, so by the time
`learn()` read dopamine the boost was gone: the focus-word feature had never done anything. Now
split into tonic and phasic, with the RPE riding on the tonic level instead of replacing it.

### The lesson

**A numerical failure that produces clean-looking output is worse than a crash.** With no
framework underneath, nothing watches for divergence unless it is written in on purpose — and
in a recurrent generative model, the two things most worth bounding are the quantity inside the
loop (activations) and the quantity that sets the loop's gain (the weight spectrum).

**A bound that is shared is a bound that is competed for.** Three of the four failures here came
from a fix for the previous one. Constraining an aggregate — one RMS over a whole link — silently
creates a zero-sum game between the things inside it, and the loser is whichever signal has the
weakest gradient rather than whichever matters least. Constrain per pathway, or expect the quiet
one to be eliminated.

## 6b. Three diffusion-adjacent modes (and why they aren't diffusion)

Diffusion sampling is annealed Langevin dynamics on a learned score function. Predictive-coding
inference is gradient descent on a free-energy landscape. Those are the same *kind* of process —
iterative refinement of a state toward higher probability under a learned model. They differ in
how the landscape is learned, and in whether noise is injected while descending it.

That makes three routes available to Nora, none of which require becoming a diffusion model.
All three keep saccadic canvas integration, which is a memory strategy rather than a generation
algorithm; what changes is the settling dynamics underneath. Plain prompts stay on the
deterministic default.

**`/sample` — annealed stochastic settling.** A Langevin update is
`x ← x − η∇E + √(2ηT)·ξ`. The existing inference loop is already the deterministic gradient
term; this adds the noise term with a geometrically annealed temperature (Song & Ermon 2019 for
why geometric). The hierarchy samples from the distribution instead of collapsing to its mode,
so the same prompt gives a different draw each time. The biological warrant is the neural
sampling hypothesis — that cortical response variability *is* sampling from a posterior rather
than noise corrupting a point estimate (Hoyer & Hyvärinen 2003; Fiser, Berkes, Orbán & Lengyel
2010; Buesing et al. 2011 for the spiking version). Under that reading a deterministic cortex is
the less defensible option and this mode fixes an omission.

**`/coarse` — anneal spatial scale instead of noise.** Diffusion's most useful emergent property
is that its noise schedule implicitly orders generation from low spatial frequency to high:
global structure first, detail last. Nora already has the machinery to do that directly — a
magno/parvo split and three spatial-frequency bands in the V1 Gabor bank. Coarse channels settle
first and finer ones unlock progressively, while thalamic gain starts magno-heavy and rebalances.
This is the coarse-to-fine hypothesis, not an analogy: a fast low-spatial-frequency magnocellular
projection reaches frontal cortex ahead of the detailed ventral signal and supplies a coarse
"gist" that constrains slower fine-grained analysis (Bar 2003; Bar et al. 2006; Hegdé 2008).
Diffusion rediscovered a schedule cortex was already using.

**`/denoise on|off` — the training objective, which is the one that actually matters.** What
makes diffusion work is not really its sampling loop; it is that denoising score matching is an
extraordinarily well-conditioned objective. Every example yields dense supervision at many
corruption levels, so the model learns the shape of the landscape rather than a single point on
it. That objective needs no autodiff: form the representation from a degraded image, compute the
retinal prediction error against the clean one, and the existing local Hebbian rule does the
rest. Corruption is noise or occlusion at a strength drawn fresh per fixation, which is the part
that supplies the many-levels property. On by default.

**Honest ranking.** `/denoise` is the only one that moves sample *fidelity*, and it does so by
multiplying supervision per image — which matters enormously when the dataset is a few dozen
photos. `/coarse` buys structural coherence for almost no cost. `/sample` buys diversity, and
will mostly expose how thin the learned landscape currently is rather than improve it: sampling
from a weak landscape gives varied weak samples. Adopting diffusion's sampling procedure does
not import diffusion's quality, which comes from an enormous dataset and a high-capacity network
with global receptive fields — none of which Nora has.

**Honesty flag on cost:** `/sample` uses 32 settling iterations against the default 20, and
`/coarse` uses 28. Both are correspondingly slower per saccade.

## 6c. Feedback: the thumbs, and the distal reward problem

The thumbs under a generated image are a three-factor learning rule, and the reason they need
one is worth stating before the mechanism.

**The problem.** The local Hebbian rule needs a presynaptic rate and a postsynaptic error signal
*present at the synapse*. A rating arrives long after both are gone — seconds if the user is
quick, days if they are not, and possibly across a process death. There is nothing left to
modulate. This is the distal reward problem, and biology's answer is not to make plasticity
faster but to make it *deferred*: coincident activity lays down a synapse-local **eligibility
trace**, and a neuromodulator arriving later converts the trace into an actual weight change
(Izhikevich 2007, which is the canonical demonstration; Frémaux & Gerstner 2016 for the review;
Gerstner et al. 2018 for traces at behavioural timescales; Frey & Morris 1997 for the molecular
substrate in synaptic tagging and capture).

**Why the trace here is an episode, not a per-synapse tag.** A literal eligibility trace means an
extra float per synapse, snapshotted per generation and persisted so a rating given tomorrow
still lands — about 550 KB per image. Nora already contains a structure whose entire purpose is
holding an experience across an arbitrary delay so it can become cortical change later: the
hippocampal episode. Storing the semantic cue and the IT pattern (~7 KB) and **replaying** them
when the rating arrives reaches the same synapses through the mechanism that exists for exactly
this, and it is what the sleep phase already does for consolidation. The replay re-instates the
prediction errors; the neuromodulator signs them.

**The signed rule.** Weight change is proportional to `(dopamine − baseline)`. A burst gives
potentiation, a dip gives depression, from one expression — which is what makes it a reward
prediction error rather than two unrelated rules bolted together. A negative RPE really is
signalled by dopamine falling below baseline rather than by the absence of a burst (Schultz,
Dayan & Montague 1997; Bayer & Glimcher 2005 measured the signed encoding directly).

| | thumbs up | thumbs down |
|---|---|---|
| dopamine | 2.2 (burst) | 0.4 (dip) |
| cortical links | potentiate, gain permanence | depress, no permanence |
| IT topographic map | pulled toward the pattern | left alone |
| ATL association | Hebbian bind, strengthened | anti-Hebbian, unwound |
| hippocampal tag | promoted → replayed more | demoted → toward eviction |
| next generation | prior raised, settles harder | seeded off-attractor, jittered |

**Why depression alone would not have been enough.** Depressing the synapses that produced a
rejected image makes its attractor shallower, not absent — deterministic settling would still
fall into it and return a slightly degraded copy of the picture the user just rejected. So a
disliked concept also gets displaced starting conditions and a low-temperature jitter that decays
across the schedule. That is the noradrenergic half: NE's computational role is to *abandon* a
failed hypothesis rather than incrementally correct it (Bouret & Sara 2005), and a rejected image
is exactly a failed hypothesis. The two halves are separable and only the pair does the job.

**Generalization, and its ceiling.** Feedback is scored per *word*, not per exact prompt string,
so rating "a redhead in a red dress" teaches something about "redhead" rather than only about
that one string. Per-word is also precisely as fine-grained as the representation underneath it
can support — the semantic hub is a bag of words with no compositionality (§4), so nothing finer
would mean anything.

**Honesty flags.**

- A thumbs-down is a negative reward prediction error with an aversive tag. It is not guilt,
  shame, or anxiety, and nothing here models an affective state. The subjective vocabulary is a
  shorthand for what the signal *does* — depress what produced this, do not go back there.
- One thumb is one example against a dataset seen many times over, so `FEEDBACK_GAIN` is 3× the
  ordinary rate. That is a tuned constant chosen so the buttons are not decoration, not a
  measured quantity, and it is the most likely thing in this section to need revisiting.
- Traces are capped at the last 16 generations. Rating something older reports honestly that it
  is out of reach rather than silently reinforcing nothing. Unapplied ratings are exempt from
  eviction, and a rating made while training is running stays pending until the brain is free.

## 6d. Autonomous training

Opt-in, off by default, and gated on three conditions checked both at schedule time and again
when a run fires: the setting is on, the Messages page is assigned to a desktop slot (Nora is
only reachable through it, so training a brain the user cannot talk to would spend their battery
on nothing), and the dataset is non-empty.

`setRequiresDeviceIdle` is the gate rather than a screen-state heuristic — training is the most
expensive thing Prism does, and the system's own definition of "not in use" is the right one to
defer to. The worker does not train; it hands the job to `NoraService`, because one owner for a
single shared mutable connectome is the whole reason that service exists.

**The caveat that will bite in practice:** from Android 12 a background app generally cannot start
a foreground service, and a WorkManager job does not by itself grant an exemption. The
battery-optimization allowlist does — which Nora's training screen already prompts for and which
overnight training needs anyway. Without it the start is refused, so the worker retries rather
than failing silently, and the Settings subtitle says so instead of leaving a switch that appears
to work and never runs.

## 6e. Two non-saccadic routes: `/hallucinate` and `/expose`

Every route in §6b, and the default itself, keeps saccadic canvas integration -- several
fixations, each independently settled, stitched onto one canvas. `/hallucinate` and `/expose`
don't: both hold a single gaze at the canvas centre for their entire run and never call the
fixation selector. This is not a fifth `NoraImageryMode` because it isn't a different settling
dynamic for the same loop structure the way `/sample` and `/coarse` are -- it's a different loop
structure, so each lives as its own top-level generation method on `MentalImagery` instead.

**Provenance, stated plainly.** Both are translations of two generation modes from AetherCortex,
a sibling brain-based project (`C:\Users\bremo\AetherCortex`, see its `main.py --biogen`). That
project has no foveated retina and no concept of a fixation at all -- its decoder maps a flat
spike-timing tensor straight to a full image every pass, so "no saccades" was already true of it
by construction. Nora's whole rendering path goes through a log-polar retina, so the honest
translation of "no saccadic drawing" here is "one fixation, never moved" rather than "no
fixation" -- there is no code path in this file that produces a canvas without going through the
retina model at least once.

**`/hallucinate` -- recursive video, self-perception instead of optic flow.** `/video` gives a
clip identity across frames by transporting V1/V2's representation forward through an
MST-synthesized flow field (§6) -- the prior for frame *t+1* is frame *t*, moved. `/hallucinate`
gives a clip continuity a different way: the prompt seeds frame 0, and every frame after that is
produced by handing the previous frame back through
[`NoraBrain.perceive`](core/src/main/kotlin/com/prism/launcher/nora/NoraBrain.kt) -- the same
bottom-up pathway real vision uses -- so IT ends up holding whatever it recognised in its own last
drawing, and THAT becomes what the next frame is imagined from. No motion field, no warp, no
guarantee content stays put:
recognising your own output and re-imagining from the recognition is a much looser coupling than
transporting a representation, and drift toward a fixed point (or toward noise) is a real outcome
of the mechanism, not a bug in it. This is the video-generation analogue of a webcam pointed at
its own monitor.

**`/expose` -- one long settle, the concept released partway through.** Every other imagery route
holds IT clamped to the prompt for its entire settle, because letting go is exactly what the
self-consistency loop would use to wander off the concept. `/expose` does that on purpose: after
`exposure_prime` iterations (30 by default) the clamp releases, and IT starts taking the same
ascending update ordinary perception gives it -- error propagated up from V4, pulled toward
silence rather than toward the prompt -- for the remaining iterations up to `exposure_total` (300
by default). The result reflects the prompt qualitatively, because it was the seed the free run
started from, but it is not obligated to still resemble it by the time 270 unclamped iterations
have run. `NoraBrain.imagine`'s `freeRunAfter` parameter is the whole mechanism; it is additive
(default `null`, meaning "never release") and every existing caller is bit-identical without it.

**Honesty flag on cost.** `/expose` is a single fixation settling for up to 300 iterations against
the default's 20 iterations × 6 fixations = 120 -- more total settle work than any other route,
paid serially rather than spread across saccades, which is why its progress narration warns it is
slow before it starts.

**Honesty flag on what "free-running" means here.** IT has nothing above it in the hierarchy, so
there is no learned prior pulling its unclamped representation anywhere in particular -- the pull
term in `CorticalRegion.updateRepresentation` contracts toward `topDown`, which for IT is always
zero, so the released concept decays toward silence at a rate set by ascending V4 error fighting
that decay. This is not "the model dreams up something new" in any richer sense; it is ordinary
predictive-coding perception dynamics applied to a representation that happens to have been
seeded by a prompt rather than by a photograph. Calling it "dreaming" is doing the same
communicative work "hallucinate" and "expose" are doing elsewhere in this section -- useful
shorthand for what the mechanism does, not a claim about what it's like to be the mechanism doing
it.

## 7. Evaluation

Both axes matter and they are not the same axis.

### Sample quality — expected, not measured

No FID/FVD/CLIP numbers are reported here because none have been run. Stating them without
running them would be fabrication. What can be said from the architecture:

- FID will be **poor** against any diffusion baseline. ~150k parameters trained from scratch on
  a hand-assembled phone dataset is not competitive and is not trying to be.
- CLIP score will be **weak**, bounded by the bag-of-words semantic hub.
- FVD *relative to* frame-wise sampling should be **favourably affected** by the clamped-concept
  mechanism, since temporal consistency is what FVD is most sensitive to. Whether that survives
  the low per-frame quality is exactly the kind of thing that needs measuring rather than
  asserting.

### Neuro-validity — the axis where Nora should actually do well

This is where a Brain-Score-style evaluation belongs, and it is a Phase 2 deliverable:

- **Neural predictivity** for V1/V4/IT against public benchmark datasets.
- **Tuning-curve comparisons**: V1 orientation bandwidth (expect ~40° half-width), MT direction
  tuning, V4 curvature tuning against Pasupathy & Connor's measured population.
- **Illusion reproduction** — the cheapest and most diagnostic tests, each targeting a specific
  implemented mechanism:
  - *Kanizsa illusory contours* → V2 collinear facilitation
  - *Tilt illusion* → V1 cross-orientation normalization
  - *Motion aftereffect* → MT opponency with adaptation
  - *Craik-O'Brien-Cornsweet* → V4 boundary-gated fill-in
  - *Simultaneous contrast* → retinal centre-surround plus fill-in

If Nora reproduces those and a diffusion model does not, that is the result worth having. It is
also falsifiable, which is more than "brain-inspired" usually offers.

### Ablations owed

Each biological component should be shown to earn its compute. Not yet run. The ones most at
risk of not earning it: the V2 texture statistics, the konio pathway, and the SOM topographic
bias.

---

## 8. Phased roadmap

**Phase 1 — shipped in this commit.** Full ventral stack (retina→LGN→V1→V2→V4→IT) as a
predictive-coding hierarchy with local learning; dorsal stream (MT/MST); laminar microcircuit;
neuromodulators; hippocampus with replay; semantic hub; mental-imagery generation; saccadic
refinement; video via temporal predictive coding; wake/sleep training; MP4 encoding; Messages
integration; checkpointed persistence.

Scale: 128×128 canvas, 32×48 cortical sheet, ~150k learned parameters.

**Phase 2 — validation and quality.**
- Brain-Score-style neural predictivity harness and the illusion battery above.
- Component ablations.
- Higher canvas resolution via more saccades (the architecture already supports it; it is a
  runtime knob).
- Proper biphasic magno temporal filtering and a longer motion history buffer.
- Reciprocal MT↔ventral connections instead of warp-only.
- NNAPI or RenderScript-successor offload for the convolution inner loops.

**Phase 3 — the hard gap.**
- A compositional binding mechanism to replace the bag-of-words hub. This is the largest single
  quality lever and the hardest item on the list.
- Learned rather than installed V4 curvature basis.
- Developmental orientation-map formation instead of an installed pinwheel map.
- Scene-level generation with multiple objects and genuine content change across frames.

Do not attempt Phase 3 before Phase 2's measurements exist. Without them there is no way to
tell whether a change helped.

---

## 9. Using her

**Chat** — Messages page → Nora.

```
a red apple on a table       generate a still
video: a red apple           generate a clip
motion: rotate-cw            set camera motion for the next clip
/status                      live brain state
/brain                       architecture summary
/train                       open the training page
/forget                      erase the connectome
```

**Rating what she makes** — every generated image and clip gets a thumbs up / thumbs down pair
under it. Tapping the bubble is a quick thumbs up and dismisses the buttons; holding it brings
them back so a rating can be given, changed, or reconsidered. See §6c for what each one actually
does to her — briefly: up potentiates the pathway that produced the image and promotes it for
replay, down depresses it and makes her start somewhere else next time you ask.

**Autonomous training** — Settings → Intelligence & Messaging → Nora → Autonomous training. Off
by default. When on, an idle phone triggers a short training run every N hours (1–24, default
10). Needs the Messages page on a desktop slot and Prism exempted from battery optimization —
see §6d.

**Brain size** — Settings → Intelligence & Messaging → **Nora**, which is now her own screen
rather than a block inside the AI options.

Every box is in **neurons**, because that's the thing worth controlling; rings-times-wedges is an
implementation detail. A typed number is a *request* — the geometry solver finds the nearest
legal configuration and redraws every box from the result, so values often come back slightly
different. That is not sloppiness: legal sizes are a discrete ladder (multiples of 8 on the
sheet, 3 on V1's orientations, 2 on MT's directions), and accepting an off-ladder value would
give a hierarchy whose halvings are inexact. That doesn't crash — it misaligns every prediction
silently, which is worse.

**Total neurons** rescales everything together. The **Max** button finds the largest size whose
estimated footprint fits 35% of the app's heap. Per-region boxes: editing the **retina sheet**
resizes V1, V2, V4, IT and MT with it, because they are *defined* as fractions of it; everything
else changes only its own channel count. Solving those by resizing the sheet instead would be
ambiguous — V2 could reach a given size by growing the retina or by growing its own channels, and
there's no principled way to pick.

Read the **training cost** figure, not the neuron count. Compute is `topC × botC × k² × topH ×
topW`, so it scales as a *product*: doubling channels and resolution is ~16× the work, not 4×.
The device maximum is bounded by memory, not by patience — it will happily suggest a size whose
full training run takes days.

Each size keeps **its own connectome file**, so changing this parks the brain trained at the
previous size instead of deleting it, and changing back restores it.

**Test this size** trains on nine procedurally-generated coloured shapes in a sandbox — its own
brain, nothing loaded or saved — and reports three things the estimates can't: whether it
actually allocates (an `OutOfMemoryError` is caught and reported rather than crashing), whether
it trains without diverging, and whether it renders a non-degenerate dynamic range. Shapes rather
than photographs because each caption word picks out exactly one visual property, so a failure is
attributable to the size rather than to the data.

**Training** — Settings → Intelligence & Messaging → Nora → Train Nora, or `/train` in her thread.

The training screen has two tabs. **Log** shows epoch, sample, prediction error and the caption
being learned. **Brain** is a live lateral view of the connectome: regions glow in proportion to
their activity, and signal packets stream along the real pathways — ascending traffic is
prediction error, descending is predictions, and both are drawn, because the two-way flow *is*
the architecture. Areas Nora does not implement (frontal, motor, cerebellum, brainstem) are
drawn but stay dark, which is honest about how much of a brain this isn't.

The visualizer renders at ~30 fps on its own thread at below-normal priority, reads telemetry
through a lock-free snapshot so it can never stall a training step, and pauses entirely when
its tab is hidden or the app is backgrounded. It is still real work on a saturated phone —
turn it off under Settings → Intelligence & Messaging → Nora → Connectome visualization.

**Dataset** — drop images into `Prism/Nora/dataset/`. The **filename is the caption**:
`a red apple on a table.png` teaches exactly that. Same convention as AetherCortex, so existing
datasets carry over.

**Compute, honestly:** roughly 1–3 s per training image per epoch on a Snapdragon 8 Gen 1. A
hundred images for twenty epochs is an hour or two — run it plugged in. A still image takes tens
of seconds to generate; a clip takes minutes. That is slow against a GPU and fast against
"impossible", which is what training a diffusion model on this hardware would be.

Everything checkpoints every epoch, so the process can be killed at any point and lose at most
one epoch. Note this is *checkpointing*, not out-of-core compute — at ~150k parameters the whole
connectome fits in RAM many times over, and streaming weights from disk during a forward pass
would be pure overhead.

**Expectations.** Early output looks like a visual system dreaming: oriented structure, coherent
surfaces, plausible colour fields, and not much object identity. That is what an undertrained
cortex with 150k synapses produces. It is honest about what it is.
