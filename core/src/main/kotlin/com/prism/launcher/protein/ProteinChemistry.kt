package com.prism.launcher.protein

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The chemistry the model is not allowed to get wrong.
 *
 * Everything here is measurement, not modelling: bond lengths from small-molecule crystallography,
 * the twenty residues, the file formats the Protein Data Bank actually distributes. A folding model
 * predicts where a chain goes; it does not get to predict that a peptide bond is 1.6 Å long, and
 * building those constants into the structure module rather than learning them is exactly what
 * makes a small model produce chemically sensible output — the same reasoning AlphaFold gives for
 * its own idealised residue frames.
 */
object ProteinChemistry {

    /**
     * The residue alphabet, in the order used everywhere else in this package.
     *
     * The twenty standard residues, then three special tokens. The order is the conventional
     * alphabetical-by-one-letter-code order that UniProt, ESM and AlphaFold all use, so a weight
     * file or a dataset written against any of them lines up with this one.
     */
    const val ALPHABET = "ACDEFGHIKLMNPQRSTVWY"

    const val UNKNOWN_INDEX = 20
    const val MASK_INDEX = 21
    const val PAD_INDEX = 22

    /** Twenty residues plus unknown, mask and pad. */
    const val VOCAB_SIZE = 23

    private val INDEX_OF: IntArray = IntArray(128) { UNKNOWN_INDEX }.also { table ->
        ALPHABET.forEachIndexed { i, c ->
            table[c.code] = i
            table[c.lowercaseChar().code] = i
        }
    }

    fun indexOf(residue: Char): Int =
        if (residue.code in 0 until 128) INDEX_OF[residue.code] else UNKNOWN_INDEX

    fun charOf(index: Int): Char = when {
        index in 0 until ALPHABET.length -> ALPHABET[index]
        index == MASK_INDEX -> '?'
        else -> 'X'
    }

    fun encode(sequence: String): IntArray =
        sequence.filter { !it.isWhitespace() }.map { indexOf(it) }.toIntArray()

    fun decode(indices: IntArray): String =
        indices.joinToString("") { charOf(it).toString() }

    /** Three-letter PDB residue names to one-letter codes, including the common modified ones. */
    private val THREE_LETTER = mapOf(
        "ALA" to 'A', "ARG" to 'R', "ASN" to 'N', "ASP" to 'D', "CYS" to 'C',
        "GLN" to 'Q', "GLU" to 'E', "GLY" to 'G', "HIS" to 'H', "ILE" to 'I',
        "LEU" to 'L', "LYS" to 'K', "MET" to 'M', "PHE" to 'F', "PRO" to 'P',
        "SER" to 'S', "THR" to 'T', "TRP" to 'W', "TYR" to 'Y', "VAL" to 'V',
        // Modified residues that appear constantly in deposited structures. Mapping them to their
        // parent residue keeps a chain contiguous; dropping them would punch holes in the backbone
        // and every one of those holes becomes a false chain break in the training target.
        "MSE" to 'M', "SEC" to 'C', "PYL" to 'K', "HYP" to 'P', "SEP" to 'S',
        "TPO" to 'T', "PTR" to 'Y', "CSO" to 'C', "CME" to 'C', "MLY" to 'K',
    )

    fun fromThreeLetter(name: String): Char? = THREE_LETTER[name.trim().uppercase()]

    // ── Ideal backbone geometry ────────────────────────────────────────────
    //
    // Engh & Huber's restrained-refinement values, which is what every structure-prediction
    // package builds idealised backbones from. Ångströms and degrees.

    const val BOND_N_CA = 1.458f
    const val BOND_CA_C = 1.525f
    const val BOND_C_N = 1.329f
    const val BOND_C_O = 1.231f
    const val BOND_CA_CB = 1.532f

    const val ANGLE_N_CA_C = 111.0f
    const val ANGLE_CA_C_N = 116.2f
    const val ANGLE_C_N_CA = 121.7f

    /**
     * Cα–Cα spacing across a trans peptide bond.
     *
     * 3.80 Å is not a free parameter — it follows from the three bond lengths and two angles above
     * with omega at 180°. It is stated separately because it is the single most useful sanity check
     * on a predicted chain: a model that produces consecutive Cα atoms at 2 Å or 6 Å has produced
     * something that is not a protein, whatever its loss says.
     */
    const val CA_CA_TRANS = 3.80f

    /** Cis peptide bonds (omega near 0) essentially only occur before proline. */
    const val CA_CA_CIS = 2.94f

    /**
     * Where Cβ sits relative to the backbone frame, in Ångströms.
     *
     * AlphaFold's distogram is defined on Cβ (Cα for glycine), so predicting contacts needs this
     * even though the model never predicts side chains. Derived from ideal geometry with the
     * standard tetrahedral arrangement at Cα, in the frame [RigidFrame.fromBackbone] builds.
     */
    val CB_OFFSET = floatArrayOf(-0.5266f, -0.5570f, -1.2075f)

    /** The residue with no side chain: its "Cβ" is its Cα, everywhere. */
    const val GLYCINE = 'G'

    // ── Distogram binning ──────────────────────────────────────────────────
    //
    // AlphaFold's own: 64 bins of Cβ–Cβ distance from 2 Å to 22 Å, with the last bin catching
    // everything further. The upper edge matters -- 22 Å is far beyond a contact, so the head
    // learns "definitely not in contact" as a real class rather than as the absence of one.

    const val DISTOGRAM_BINS = 64
    const val DISTOGRAM_MIN = 2.0f
    const val DISTOGRAM_MAX = 22.0f

    fun distogramBin(distance: Float): Int {
        if (distance.isNaN()) return DISTOGRAM_BINS - 1
        val width = (DISTOGRAM_MAX - DISTOGRAM_MIN) / (DISTOGRAM_BINS - 1)
        val bin = ((distance - DISTOGRAM_MIN) / width).toInt()
        return bin.coerceIn(0, DISTOGRAM_BINS - 1)
    }

    fun distogramCentre(bin: Int): Float {
        val width = (DISTOGRAM_MAX - DISTOGRAM_MIN) / (DISTOGRAM_BINS - 1)
        return DISTOGRAM_MIN + width * bin
    }

    /** Two residues are "in contact" at 8 Å Cβ–Cβ — the CASP definition, unchanged for decades. */
    const val CONTACT_THRESHOLD = 8.0f
}

/**
 * One residue's rigid frame: a rotation and a translation, in the sense AlphaFold's structure
 * module uses.
 *
 * ## Why a frame rather than three atoms
 *
 * Because the structure module predicts *updates to frames*, not atom positions, and the loss is
 * computed after mapping every atom into every residue's local frame. That is what makes the whole
 * thing invariant to where the protein sits in space: rotating the answer rotates nothing that is
 * scored. A model that predicted raw coordinates would spend most of its capacity learning that
 * global orientation does not matter.
 *
 * The frame is built from the backbone by Gram–Schmidt on (N → Cα) and (C → Cα), with the
 * translation at Cα. Deterministic, and exactly reversible for a well-formed residue.
 */
data class RigidFrame(
    /** Row-major 3x3 rotation. */
    val rotation: FloatArray,
    val translation: FloatArray,
) {
    init {
        require(rotation.size == 9) { "rotation must be 3x3" }
        require(translation.size == 3) { "translation must be 3 long" }
    }

    /** Local point to global: `R @ p + t`. */
    fun apply(point: FloatArray): FloatArray = floatArrayOf(
        rotation[0] * point[0] + rotation[1] * point[1] + rotation[2] * point[2] + translation[0],
        rotation[3] * point[0] + rotation[4] * point[1] + rotation[5] * point[2] + translation[1],
        rotation[6] * point[0] + rotation[7] * point[1] + rotation[8] * point[2] + translation[2],
    )

    /** Global point to local: `R^T @ (p - t)`. The inverse, without inverting anything. */
    fun invert(point: FloatArray): FloatArray {
        val d0 = point[0] - translation[0]
        val d1 = point[1] - translation[1]
        val d2 = point[2] - translation[2]
        return floatArrayOf(
            rotation[0] * d0 + rotation[3] * d1 + rotation[6] * d2,
            rotation[1] * d0 + rotation[4] * d1 + rotation[7] * d2,
            rotation[2] * d0 + rotation[5] * d1 + rotation[8] * d2,
        )
    }

    /** Composes this frame with another: the result applies [other] first, then this. */
    fun compose(other: RigidFrame): RigidFrame {
        val r = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var acc = 0f
            for (k in 0 until 3) acc += rotation[i * 3 + k] * other.rotation[k * 3 + j]
            r[i * 3 + j] = acc
        }
        return RigidFrame(r, apply(other.translation))
    }

    override fun equals(other: Any?): Boolean =
        other is RigidFrame &&
            rotation.contentEquals(other.rotation) &&
            translation.contentEquals(other.translation)

    override fun hashCode(): Int = rotation.contentHashCode() * 31 + translation.contentHashCode()

    companion object {
        fun identity(): RigidFrame =
            RigidFrame(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f))

        /**
         * The frame of a residue from its backbone atoms.
         *
         * Gram–Schmidt, following AlphaFold's `rigidFrom3Points`: the x axis runs Cα→C, the y axis
         * is whatever of Cα→N is perpendicular to it, and z completes a right-handed set. Any three
         * non-collinear points would give *a* frame; this particular one is the convention the
         * published Cβ offset and torsion definitions are written against, so it is not
         * interchangeable with another choice.
         */
        fun fromBackbone(n: FloatArray, ca: FloatArray, c: FloatArray): RigidFrame {
            val x = normalise(sub(c, ca))
            val tmp = sub(n, ca)
            val dot = x[0] * tmp[0] + x[1] * tmp[1] + x[2] * tmp[2]
            val y = normalise(floatArrayOf(tmp[0] - dot * x[0], tmp[1] - dot * x[1], tmp[2] - dot * x[2]))
            val z = cross(x, y)
            return RigidFrame(
                floatArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]),
                ca.copyOf(),
            )
        }

        /**
         * A rotation from an unnormalised quaternion, which is how the structure module emits one.
         *
         * Unnormalised on purpose: AlphaFold predicts the three imaginary components with the real
         * part pinned at 1 and then normalises, which cannot represent a rotation of more than
         * 180° in a single step. That is a feature — it bounds how far one update can move a
         * residue, and the module is run several times.
         */
        fun fromQuaternion(b: Float, c: Float, d: Float): FloatArray {
            val a = 1f
            val inv = 1f / sqrt(a * a + b * b + c * c + d * d)
            val qa = a * inv
            val qb = b * inv
            val qc = c * inv
            val qd = d * inv
            return floatArrayOf(
                qa * qa + qb * qb - qc * qc - qd * qd, 2 * (qb * qc - qa * qd), 2 * (qb * qd + qa * qc),
                2 * (qb * qc + qa * qd), qa * qa - qb * qb + qc * qc - qd * qd, 2 * (qc * qd - qa * qb),
                2 * (qb * qd - qa * qc), 2 * (qc * qd + qa * qb), qa * qa - qb * qb - qc * qc + qd * qd,
            )
        }

        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

        fun normalise(v: FloatArray): FloatArray {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (n < 1e-8f) return floatArrayOf(1f, 0f, 0f)
            return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }

        fun distance(a: FloatArray, b: FloatArray): Float {
            val dx = a[0] - b[0]
            val dy = a[1] - b[1]
            val dz = a[2] - b[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}

/**
 * A protein chain: its sequence, and where its backbone atoms are when that is known.
 *
 * Coordinates are optional per residue. A chain read from a sequence database has none; a chain
 * read from the PDB usually has them for most residues and not for the disordered ends, and
 * pretending otherwise is how a training set teaches a model that flexible loops sit at the origin.
 */
data class ProteinChain(
    val id: String,
    val sequence: String,
    /** Per residue: N, CA, C as 9 floats, or null where the residue was not resolved. */
    val backbone: Array<FloatArray?>? = null,
    val source: String = "",
) {
    val length: Int get() = sequence.length

    val hasStructure: Boolean get() = backbone != null && backbone.any { it != null }

    val resolvedCount: Int get() = backbone?.count { it != null } ?: 0

    fun caOf(index: Int): FloatArray? =
        backbone?.getOrNull(index)?.let { floatArrayOf(it[3], it[4], it[5]) }

    fun frameOf(index: Int): RigidFrame? {
        val b = backbone?.getOrNull(index) ?: return null
        return RigidFrame.fromBackbone(
            floatArrayOf(b[0], b[1], b[2]),
            floatArrayOf(b[3], b[4], b[5]),
            floatArrayOf(b[6], b[7], b[8]),
        )
    }

    /**
     * Cβ, or Cα for glycine — the atom the distogram is actually defined on.
     *
     * Built from the frame rather than read from the file, so it exists for every resolved residue
     * even when the side chain was not modelled, and so a predicted structure and a true one are
     * measured the same way.
     */
    fun cbOf(index: Int): FloatArray? {
        val frame = frameOf(index) ?: return null
        if (sequence.getOrNull(index) == ProteinChemistry.GLYCINE) return frame.translation.copyOf()
        return frame.apply(ProteinChemistry.CB_OFFSET)
    }

    override fun equals(other: Any?): Boolean = other is ProteinChain && id == other.id && sequence == other.sequence

    override fun hashCode(): Int = id.hashCode() * 31 + sequence.hashCode()
}

/**
 * Reading the two file formats that matter, and nothing else.
 *
 * FASTA for sequence databases (UniRef, UniProt, anything the user drops in), PDB and mmCIF for
 * resolved structures. mmCIF because the PDB stopped being able to express large structures in the
 * fixed-column PDB format years ago and now distributes mmCIF as the canonical form; PDB format
 * because everything the user already has on disk is probably in it.
 */
object ProteinParsers {

    /** Streams FASTA records without holding the file in memory. Sequence databases are large. */
    fun parseFasta(text: String, limit: Int = Int.MAX_VALUE): List<ProteinChain> {
        val out = ArrayList<ProteinChain>()
        var header: String? = null
        val current = StringBuilder()

        fun flush() {
            val h = header ?: return
            if (current.isNotEmpty()) {
                out.add(ProteinChain(id = h, sequence = current.toString().uppercase(), source = "fasta"))
            }
            current.setLength(0)
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith(">") -> {
                    flush()
                    if (out.size >= limit) return out
                    header = line.drop(1).takeWhile { !it.isWhitespace() }.ifBlank { "seq${out.size}" }
                }
                else -> current.append(line.filter { it.isLetter() || it == '-' })
            }
        }
        flush()
        return out
    }

    /**
     * Parses the backbone out of a PDB-format file.
     *
     * Only ATOM records, only N/CA/C, only the first altLoc, and only one chain at a time. Hetero
     * atoms, waters, ligands and alternate conformations are all things a backbone predictor has no
     * use for, and each of them is a way to end up with a chain whose residue numbering does not
     * match its own sequence.
     */
    fun parsePdb(text: String, chainId: String? = null): List<ProteinChain> {
        data class Residue(val seq: Int, val code: Char, val atoms: HashMap<String, FloatArray>)

        val chains = LinkedHashMap<String, LinkedHashMap<Int, Residue>>()

        text.lineSequence().forEach { line ->
            if (!line.startsWith("ATOM")) return@forEach
            if (line.length < 54) return@forEach
            val altLoc = line[16]
            if (altLoc != ' ' && altLoc != 'A') return@forEach

            val atomName = line.substring(12, 16).trim()
            if (atomName != "N" && atomName != "CA" && atomName != "C") return@forEach

            val resName = line.substring(17, 20)
            val code = ProteinChemistry.fromThreeLetter(resName) ?: return@forEach
            val chain = line.substring(21, 22).trim().ifEmpty { "A" }
            if (chainId != null && chain != chainId) return@forEach
            val resSeq = line.substring(22, 26).trim().toIntOrNull() ?: return@forEach

            val x = line.substring(30, 38).trim().toFloatOrNull() ?: return@forEach
            val y = line.substring(38, 46).trim().toFloatOrNull() ?: return@forEach
            val z = line.substring(46, 54).trim().toFloatOrNull() ?: return@forEach

            val residues = chains.getOrPut(chain) { LinkedHashMap() }
            val residue = residues.getOrPut(resSeq) { Residue(resSeq, code, HashMap()) }
            residue.atoms[atomName] = floatArrayOf(x, y, z)
        }

        return chains.map { (chain, residues) -> assemble(chain, residues.values.map { Triple(it.seq, it.code, it.atoms) }) }
            .filter { it.length > 0 }
    }

    /**
     * Parses the backbone out of an mmCIF file.
     *
     * Reads the `_atom_site` loop by column name rather than by position, which is the whole reason
     * mmCIF exists: the columns are not in a fixed order and a parser that assumes one works on the
     * files it was written against and silently mis-reads the rest.
     */
    fun parseMmcif(text: String, chainId: String? = null): List<ProteinChain> {
        val lines = text.lines()
        var i = 0
        val columns = ArrayList<String>()
        var inLoop = false

        data class Residue(val seq: Int, val code: Char, val atoms: HashMap<String, FloatArray>)
        val chains = LinkedHashMap<String, LinkedHashMap<Int, Residue>>()

        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line == "loop_" -> {
                    columns.clear()
                    inLoop = false
                    i++
                    while (i < lines.size && lines[i].trim().startsWith("_")) {
                        columns.add(lines[i].trim())
                        i++
                    }
                    inLoop = columns.any { it.startsWith("_atom_site.") }
                    continue
                }
                inLoop && line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("_") -> {
                    val fields = splitCifRow(line)
                    if (fields.size >= columns.size) {
                        fun field(name: String): String? {
                            val at = columns.indexOf("_atom_site.$name")
                            return if (at >= 0 && at < fields.size) fields[at] else null
                        }
                        val group = field("group_PDB") ?: "ATOM"
                        val atomName = field("label_atom_id")?.trim('"')
                        if (group == "ATOM" && (atomName == "N" || atomName == "CA" || atomName == "C")) {
                            val altLoc = field("label_alt_id") ?: "."
                            if (altLoc == "." || altLoc == "A") {
                                val code = ProteinChemistry.fromThreeLetter(field("label_comp_id").orEmpty())
                                val chain = field("auth_asym_id") ?: field("label_asym_id") ?: "A"
                                val seq = (field("auth_seq_id") ?: field("label_seq_id"))?.toIntOrNull()
                                val x = field("Cartn_x")?.toFloatOrNull()
                                val y = field("Cartn_y")?.toFloatOrNull()
                                val z = field("Cartn_z")?.toFloatOrNull()
                                if (code != null && seq != null && x != null && y != null && z != null &&
                                    (chainId == null || chain == chainId)
                                ) {
                                    val residues = chains.getOrPut(chain) { LinkedHashMap() }
                                    val residue = residues.getOrPut(seq) { Residue(seq, code, HashMap()) }
                                    residue.atoms[atomName] = floatArrayOf(x, y, z)
                                }
                            }
                        }
                    }
                    i++
                    continue
                }
                line.startsWith("#") -> inLoop = false
            }
            i++
        }

        return chains.map { (chain, residues) -> assemble(chain, residues.values.map { Triple(it.seq, it.code, it.atoms) }) }
            .filter { it.length > 0 }
    }

    /** mmCIF quotes fields containing spaces; everything else is whitespace-separated. */
    private fun splitCifRow(line: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < line.length) {
            when {
                line[i].isWhitespace() -> i++
                line[i] == '\'' || line[i] == '"' -> {
                    val quote = line[i]
                    val end = line.indexOf(quote, i + 1)
                    if (end < 0) {
                        out.add(line.substring(i + 1)); i = line.length
                    } else {
                        out.add(line.substring(i + 1, end)); i = end + 1
                    }
                }
                else -> {
                    val end = line.indexOfFirst(i) { it.isWhitespace() }
                    out.add(line.substring(i, end)); i = end
                }
            }
        }
        return out
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    /**
     * Turns a residue map into a chain, filling gaps in the numbering.
     *
     * A missing residue number is a real gap in the deposited model, not a numbering quirk, so it
     * becomes an unresolved position rather than being closed up. Closing it would join two
     * residues that are nowhere near each other and hand the trainer a 15 Å "peptide bond".
     */
    private fun assemble(
        chainId: String,
        residues: List<Triple<Int, Char, HashMap<String, FloatArray>>>,
    ): ProteinChain {
        if (residues.isEmpty()) return ProteinChain(chainId, "")
        val sorted = residues.sortedBy { it.first }
        val first = sorted.first().first
        val last = sorted.last().first
        // A pathological numbering (an insertion code range, a renumbered ligand) must not make
        // this allocate a million empty residues.
        val span = (last - first + 1).coerceAtMost(sorted.size * 4 + 64)

        val sequence = CharArray(span) { 'X' }
        val backbone = arrayOfNulls<FloatArray>(span)
        sorted.forEach { (seq, code, atoms) ->
            val at = seq - first
            if (at !in 0 until span) return@forEach
            sequence[at] = code
            val n = atoms["N"]
            val ca = atoms["CA"]
            val c = atoms["C"]
            if (n != null && ca != null && c != null) {
                backbone[at] = floatArrayOf(n[0], n[1], n[2], ca[0], ca[1], ca[2], c[0], c[1], c[2])
            }
        }
        return ProteinChain(chainId, String(sequence), backbone, source = "structure")
    }
}

/**
 * The measurements that say whether a prediction is any good.
 *
 * All three are the standard ones, and all three are reported rather than just one, because they
 * disagree in informative ways: RMSD is dominated by the worst-placed domain, TM-score is not,
 * and lDDT does not superimpose anything at all so it survives a correct structure whose domains
 * are hinged differently from the reference.
 */
object StructureMetrics {

    /**
     * Kabsch superposition: the rotation that minimises RMSD between two point sets.
     *
     * Implemented through the covariance matrix's SVD, done here as a Jacobi eigendecomposition of
     * `H^T H` — three points' worth of linear algebra, and it avoids depending on a full SVD.
     */
    fun kabschRmsd(a: List<FloatArray>, b: List<FloatArray>): Float {
        require(a.size == b.size)
        if (a.isEmpty()) return Float.NaN
        val n = a.size

        val ca = centroid(a)
        val cb = centroid(b)
        val pa = a.map { RigidFrame.sub(it, ca) }
        val pb = b.map { RigidFrame.sub(it, cb) }

        val h = FloatArray(9)
        for (i in 0 until n) for (r in 0 until 3) for (c in 0 until 3) {
            h[r * 3 + c] += pa[i][r] * pb[i][c]
        }

        val rotation = optimalRotation(h)
        var sum = 0f
        for (i in 0 until n) {
            val rotated = floatArrayOf(
                rotation[0] * pa[i][0] + rotation[1] * pa[i][1] + rotation[2] * pa[i][2],
                rotation[3] * pa[i][0] + rotation[4] * pa[i][1] + rotation[5] * pa[i][2],
                rotation[6] * pa[i][0] + rotation[7] * pa[i][1] + rotation[8] * pa[i][2],
            )
            val d = RigidFrame.sub(rotated, pb[i])
            sum += d[0] * d[0] + d[1] * d[1] + d[2] * d[2]
        }
        return sqrt(sum / n)
    }

    /**
     * TM-score: length-normalised, so a 0.5 means the same thing for a 60-residue chain as for a
     * 600-residue one. Above 0.5 the two structures share a fold; below 0.17 the comparison is
     * indistinguishable from two random chains.
     */
    fun tmScore(predicted: List<FloatArray>, native: List<FloatArray>): Float {
        require(predicted.size == native.size)
        val n = predicted.size
        if (n == 0) return 0f
        val d0 = if (n > 15) (1.24f * Math.cbrt((n - 15).toDouble()).toFloat() - 1.8f).coerceAtLeast(0.5f) else 0.5f

        val ca = centroid(predicted)
        val cb = centroid(native)
        val pa = predicted.map { RigidFrame.sub(it, ca) }
        val pb = native.map { RigidFrame.sub(it, cb) }
        val h = FloatArray(9)
        for (i in 0 until n) for (r in 0 until 3) for (c in 0 until 3) h[r * 3 + c] += pa[i][r] * pb[i][c]
        val rotation = optimalRotation(h)

        var sum = 0f
        for (i in 0 until n) {
            val rotated = floatArrayOf(
                rotation[0] * pa[i][0] + rotation[1] * pa[i][1] + rotation[2] * pa[i][2],
                rotation[3] * pa[i][0] + rotation[4] * pa[i][1] + rotation[5] * pa[i][2],
                rotation[6] * pa[i][0] + rotation[7] * pa[i][1] + rotation[8] * pa[i][2],
            )
            val d = RigidFrame.distance(rotated, pb[i])
            sum += 1f / (1f + (d / d0) * (d / d0))
        }
        return sum / n
    }

    /**
     * lDDT: the fraction of interatomic distances reproduced within tolerance, superposition-free.
     *
     * This is the quantity AlphaFold's pLDDT head is trained to predict, which is why it is here
     * rather than only RMSD — the confidence number shown next to a prediction has to mean
     * something measurable, and this is the thing it means.
     */
    fun lddt(
        predicted: List<FloatArray?>,
        native: List<FloatArray?>,
        inclusionRadius: Float = 15f,
    ): Float {
        require(predicted.size == native.size)
        val thresholds = floatArrayOf(0.5f, 1f, 2f, 4f)
        var preserved = 0f
        var total = 0f
        for (i in predicted.indices) {
            val pi = predicted[i] ?: continue
            val ni = native[i] ?: continue
            for (j in predicted.indices) {
                if (i == j) continue
                val pj = predicted[j] ?: continue
                val nj = native[j] ?: continue
                val dNative = RigidFrame.distance(ni, nj)
                if (dNative > inclusionRadius) continue
                val dPred = RigidFrame.distance(pi, pj)
                val diff = abs(dPred - dNative)
                thresholds.forEach { t ->
                    total += 1f
                    if (diff < t) preserved += 1f
                }
            }
        }
        return if (total <= 0f) 0f else preserved / total
    }

    /** Per-residue lDDT, which is what a confidence colouring actually needs. */
    fun perResidueLddt(
        predicted: List<FloatArray?>,
        native: List<FloatArray?>,
        inclusionRadius: Float = 15f,
    ): FloatArray {
        val thresholds = floatArrayOf(0.5f, 1f, 2f, 4f)
        val out = FloatArray(predicted.size)
        for (i in predicted.indices) {
            val pi = predicted[i]
            val ni = native[i]
            if (pi == null || ni == null) continue
            var preserved = 0f
            var total = 0f
            for (j in predicted.indices) {
                if (i == j) continue
                val pj = predicted[j] ?: continue
                val nj = native[j] ?: continue
                val dNative = RigidFrame.distance(ni, nj)
                if (dNative > inclusionRadius) continue
                val diff = abs(RigidFrame.distance(pi, pj) - dNative)
                thresholds.forEach { t ->
                    total += 1f
                    if (diff < t) preserved += 1f
                }
            }
            out[i] = if (total <= 0f) 0f else preserved / total
        }
        return out
    }

    private fun centroid(points: List<FloatArray>): FloatArray {
        val c = FloatArray(3)
        points.forEach { p -> for (k in 0 until 3) c[k] += p[k] }
        val inv = 1f / max(1, points.size)
        for (k in 0 until 3) c[k] *= inv
        return c
    }

    /**
     * The rotation that best maps the first point set onto the second, by Horn's quaternion method.
     *
     * ## Why not the textbook SVD
     *
     * Because a 3x3 SVD written by hand is where this goes wrong. The determinant correction for
     * the reflection case has to be applied to the right singular vector, degenerate singular
     * values need special handling, and a sign error produces a *mirror image* — which superimposes
     * beautifully, scores a perfect RMSD, and is a physically impossible protein.
     *
     * Horn's formulation sidesteps all of it: build a symmetric 4x4 from the covariance matrix, take
     * its largest eigenvector, and read that eigenvector as a unit quaternion. A quaternion cannot
     * represent a reflection at all, so the failure mode is structurally impossible rather than
     * guarded against.
     */
    private fun optimalRotation(h: FloatArray): FloatArray {
        // h is row-major with h[r*3+c] = sum_i a_i[r] * b_i[c].
        val sxx = h[0]; val sxy = h[1]; val sxz = h[2]
        val syx = h[3]; val syy = h[4]; val syz = h[5]
        val szx = h[6]; val szy = h[7]; val szz = h[8]

        val k = floatArrayOf(
            sxx + syy + szz, syz - szy, szx - sxz, sxy - syx,
            syz - szy, sxx - syy - szz, sxy + syx, szx + sxz,
            szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy,
            sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz,
        )

        val q = largestEigenvector(k)
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return floatArrayOf(
            w * w + x * x - y * y - z * z, 2f * (x * y - w * z), 2f * (x * z + w * y),
            2f * (x * y + w * z), w * w - x * x + y * y - z * z, 2f * (y * z - w * x),
            2f * (x * z - w * y), 2f * (y * z + w * x), w * w - x * x - y * y + z * z,
        )
    }

    /**
     * The eigenvector of a symmetric 4x4 with the largest eigenvalue, by Jacobi rotations.
     *
     * Jacobi rather than power iteration because the two largest eigenvalues are close together
     * exactly when two candidate superpositions are nearly as good as each other, which is when
     * power iteration converges slowest and matters most.
     */
    private fun largestEigenvector(input: FloatArray): FloatArray {
        val a = input.copyOf()
        val v = FloatArray(16).also { for (i in 0 until 4) it[i * 4 + i] = 1f }

        repeat(64) {
            var p = 0
            var q = 1
            var best = 0f
            for (r in 0 until 4) for (c in r + 1 until 4) {
                if (abs(a[r * 4 + c]) > best) {
                    best = abs(a[r * 4 + c]); p = r; q = c
                }
            }
            if (best < 1e-10f) return@repeat

            val app = a[p * 4 + p]
            val aqq = a[q * 4 + q]
            val apq = a[p * 4 + q]
            val theta = 0.5f * (aqq - app) / apq
            val t = (if (theta >= 0f) 1f else -1f) / (abs(theta) + sqrt(theta * theta + 1f))
            val c = 1f / sqrt(t * t + 1f)
            val s = t * c

            for (r in 0 until 4) {
                val arp = a[r * 4 + p]
                val arq = a[r * 4 + q]
                a[r * 4 + p] = c * arp - s * arq
                a[r * 4 + q] = s * arp + c * arq
            }
            for (col in 0 until 4) {
                val apc = a[p * 4 + col]
                val aqc = a[q * 4 + col]
                a[p * 4 + col] = c * apc - s * aqc
                a[q * 4 + col] = s * apc + c * aqc
            }
            for (r in 0 until 4) {
                val vrp = v[r * 4 + p]
                val vrq = v[r * 4 + q]
                v[r * 4 + p] = c * vrp - s * vrq
                v[r * 4 + q] = s * vrp + c * vrq
            }
        }

        var bestCol = 0
        var bestValue = a[0]
        for (i in 1 until 4) if (a[i * 4 + i] > bestValue) { bestValue = a[i * 4 + i]; bestCol = i }

        val out = floatArrayOf(v[bestCol], v[4 + bestCol], v[8 + bestCol], v[12 + bestCol])
        val norm = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        if (norm < 1e-8f) return floatArrayOf(1f, 0f, 0f, 0f)
        for (i in 0 until 4) out[i] /= norm
        return out
    }
}
