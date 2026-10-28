package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Independent real-image, state-range and ambiguity regression fixtures for the pure attack
 * control recognizer.
 *
 * Ownership note: this class and the private fixtures under `fixture-suite/` are owned by the
 * fixture worker. It consumes only the stable pure API
 * `AttackButtonPixels.detect(width, height, rgb)` and `AttackButtonEvidenceGate(maxAgeMs)`.
 *
 * Fixtures are raw uncompressed RGB (`3 bytes/px`, row-major, R,G,B) produced on the host by
 * `fixture-suite/generate_fixtures.py` with PIL, because Android/JVM unit tests must not use
 * ImageIO. Each case carries its exact width/height, SHA-256 and a grounded label:
 *
 *  - genuine phone captures keep their original bytes (source copies + SHA-256 in the manifest);
 *    their centres are the caller's visual labels cross-checked by an independent red-component
 *    or polar-ring measurement, never by this recognizer's output;
 *  - pasted/scaled cases derive the expected centre arithmetically from the paste box and the
 *    expected radius from the genuine source ring scaled by the same factor.
 *
 * A miss, a wrong centre, a wrong radius, or an accepted ambiguity fails the test; nothing is
 * merely printed. Without `PYLA_ATTACK_STATES_FIXTURE_DIR` the private fixtures are absent and the
 * class skips by design (a skip is not a pass, see AGENTS.md).
 */
class AttackControlStateRangeTest {

    private data class Case(
        val id: String,
        val category: String,
        val file: File,
        val width: Int,
        val height: Int,
        val expectPositive: Boolean,
        val cx: Float,
        val cy: Float,
        val tol: Float,
        val radiusMin: Float,
        val radiusMax: Float,
        val sha256: String,
    )

    private val root: File? = System.getenv("PYLA_ATTACK_STATES_FIXTURE_DIR")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::File)

    private val cases: List<Case> by lazy {
        val dir = requireNotNull(root) { "fixture directory must be configured" }
        val manifest = File(dir, "cases.tsv")
        check(manifest.isFile) { "missing fixture manifest: ${manifest.absolutePath}" }
        manifest.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split('\t')
                check(f.size == 12) { "malformed fixture row (${f.size} columns): $line" }
                Case(
                    id = f[0],
                    category = f[1],
                    file = File(dir, f[2]),
                    width = f[3].toInt(),
                    height = f[4].toInt(),
                    expectPositive = f[5] == "positive",
                    cx = f[6].toFloatOrNull() ?: -1f,
                    cy = f[7].toFloatOrNull() ?: -1f,
                    tol = f[8].toFloatOrNull() ?: -1f,
                    radiusMin = f[9].toFloatOrNull() ?: -1f,
                    radiusMax = f[10].toFloatOrNull() ?: -1f,
                    sha256 = f[11],
                )
            }
            .also { rows -> check(rows.isNotEmpty()) { "fixture manifest has no cases" } }
    }

    @Before
    fun requireConfiguredFixtures() {
        // Absent private fixtures => skip (and the controller forbids a skip when the env is set),
        // but a configured directory with a broken manifest must fail, never silently pass.
        assumeTrue(
            "PYLA_ATTACK_STATES_FIXTURE_DIR is not configured; private fixtures are required",
            root?.isDirectory == true,
        )
        cases
    }

    private fun detect(c: Case): AttackButtonPoint? {
        val rgb = c.file.readBytes()
        assertEquals("fixture size for ${c.id}", c.width * c.height * 3, rgb.size)
        return AttackButtonPixels.detect(c.width, c.height, rgb)
    }

    private fun requirePositive(c: Case): AttackButtonPoint {
        val point = detect(c)
        assertNotNull("genuine/derived attack control missed: ${c.id}", point)
        val p = requireNotNull(point)
        assertTrue(
            "centre x for ${c.id}: expected ${c.cx}+-${c.tol} but was ${p.x}",
            Math.abs(p.x - c.cx) <= c.tol,
        )
        assertTrue(
            "centre y for ${c.id}: expected ${c.cy}+-${c.tol} but was ${p.y}",
            Math.abs(p.y - c.cy) <= c.tol,
        )
        if (c.radiusMin > 0f && c.radiusMax > 0f) {
            assertTrue(
                "radius for ${c.id}: expected ${c.radiusMin}..${c.radiusMax} but was ${p.radius}",
                p.radius in c.radiusMin..c.radiusMax,
            )
        }
        return p
    }

    private fun requireDenied(c: Case) {
        val point = detect(c)
        assertNull("unexpected attack control in ${c.id}: $point", point)
    }

    @Test
    fun fixtureManifestMatchesExactBytes() {
        assertEquals("unexpected fixture count", 24, cases.size)
        assertEquals(
            "unexpected genuine positive count",
            5,
            cases.count { it.category == "genuine-positive" },
        )
        assertEquals(
            "unexpected state-range positive count",
            4,
            cases.count { it.category == "state-range-positive" },
        )
        assertEquals("unexpected ambiguity count", 5, cases.count { it.category == "ambiguity" })
        assertEquals("unexpected cross-path count", 3, cases.count { it.category == "cross-path-positive" })
        for (c in cases) {
            assertTrue("missing fixture ${c.id}: ${c.file.absolutePath}", c.file.isFile)
            val bytes = c.file.readBytes()
            assertEquals("byte length for ${c.id}", c.width * c.height * 3, bytes.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals("fixture bytes changed for ${c.id}", c.sha256, digest)
        }
    }

    @Test
    fun genuinePhoneCapturesAreRecognized() {
        val genuine = cases.filter { it.category == "genuine-positive" }
        assertEquals("genuine fixtures must all be present", 5, genuine.size)
        for (c in genuine) {
            requirePositive(c)
        }
    }

    @Test
    fun derivedStateRangeAndGeometryPositivesKeepGroundedCentres() {
        val derived = cases.filter { it.category == "state-range-positive" }
        assertEquals("state-range fixtures must all be present", 4, derived.size)
        for (c in derived) {
            requirePositive(c)
        }
        // The resting and pressed fixtures are the same genuine ring at the same place: a state
        // change must not move the reported centre or invent a different radius.
        val resting = requirePositive(cases.single { it.id == "derived-translucent-resting" })
        val pressed = requirePositive(cases.single { it.id == "derived-pressed-opaque" })
        assertEquals("resting/pressed centre drift x", pressed.x, resting.x, 1.0f)
        assertEquals("resting/pressed centre drift y", pressed.y, resting.y, 1.0f)
        val ratio = pressed.radius / resting.radius
        assertTrue("resting/pressed radius ratio $ratio", ratio in 0.9f..1.1f)
    }

    @Test
    fun genuineNegativesAndDistractorsAreDenied() {
        val negatives = cases.filter { it.category == "negative" }
        assertEquals("negative fixtures must all be present", 7, negatives.size)
        for (c in negatives) {
            requireDenied(c)
        }
    }

    @Test
    fun twoIndependentControlsAreAmbiguousEvenWhenOnlyOneLooksPressed() {
        val ambiguous = cases.filter { it.category == "ambiguity" }
        assertEquals("ambiguity fixtures must all be present", 5, ambiguous.size)
        // Each placement is a valid positive on its own first, so the denial below is about the
        // second control and not about a broken patch.
        requirePositive(cases.single { it.id == "derived-translucent-relocated" })
        requirePositive(cases.single { it.id == "derived-pressed-opaque" })
        for (c in ambiguous) {
            requireDenied(c)
        }
    }

    @Test
    fun restingToPressedToRestingEvidenceTransition() {
        val restingCase = cases.single { it.id == "derived-translucent-resting" }
        val pressedCase = cases.single { it.id == "derived-pressed-opaque" }
        val resting = requirePositive(restingCase)
        val pressed = requirePositive(pressedCase)
        assertEquals(pressed.x, resting.x, 1.0f)
        assertEquals(pressed.y, resting.y, 1.0f)

        val gate = AttackButtonEvidenceGate(3000L)
        // One resting capture is not yet authority.
        assertNull(gate.observe(resting, 1_000L, 1_000L, 42L))
        // The pressed capture of the same physical control confirms it.
        val accepted = gate.observe(pressed, 1_100L, 1_100L, 42L)
        assertNotNull("pressed capture must confirm the resting capture", accepted)
        assertEquals("gate must report the observed centre", pressed.x, accepted!!.point.x, 0.01f)
        assertEquals(pressed.y, accepted.point.y, 0.01f)
        assertNotNull(gate.current(1_100L, 42L))

        // Back to resting appearance at the same place stays authorized: this is a state change,
        // not a new control.
        val back = gate.observe(resting, 1_400L, 1_400L, 42L)
        assertNotNull("resting->pressed->resting must stay authorized", back)
        assertEquals(pressed.x, back!!.point.x, 1.0f)
        assertNotNull(gate.current(1_500L, 42L))

        // Stale evidence cannot be read back as fresh (3000 ms budget, captured at 1400 ms).
        assertNull("stale evidence must be denied", gate.current(4_401L, 42L))
        // Changed geometry revokes the old coordinates.
        assertNull("geometry change must revoke", gate.current(1_500L, 43L))
        assertNull("geometry change needs reconfirmation", gate.observe(resting, 1_600L, 1_600L, 43L))
        assertNotNull(gate.observe(resting, 1_700L, 1_700L, 43L))
    }

    @Test
    fun repeatOrStaleCapturesCannotConfirmFreshEvidence() {
        val resting = requirePositive(cases.single { it.id == "derived-translucent-resting" })
        val pressed = requirePositive(cases.single { it.id == "derived-pressed-opaque" })

        val gate = AttackButtonEvidenceGate(3000L)
        assertNull(gate.observe(resting, 1_000L, 1_000L, 42L))
        // A second observation of the *same* capture is not a second, fresh capture.
        assertNull("a repeated capture must not confirm itself", gate.observe(pressed, 1_000L, 1_000L, 42L))
        assertNull(gate.current(1_000L, 42L))
        // A distinct fresh capture does confirm.
        assertNotNull(gate.observe(pressed, 1_001L, 1_001L, 42L))
        // Future/backwards timestamps fail closed.
        val fresh = AttackButtonEvidenceGate(3000L)
        assertNull(fresh.observe(resting, 2_000L, 1_900L, 42L))
        assertNull(fresh.current(1_900L, 42L))
    }

    @Test
    fun absentObservationRevokesWithoutInventingNewEvidence() {
        val resting = requirePositive(cases.single { it.id == "derived-translucent-resting" })
        val pressed = requirePositive(cases.single { it.id == "derived-pressed-opaque" })

        val gate = AttackButtonEvidenceGate(3000L)
        assertNull(gate.observe(resting, 1_000L, 1_000L, 42L))
        assertNotNull(gate.observe(pressed, 1_100L, 1_100L, 42L))
        // A null detection (control gone / ambiguous) revokes immediately.
        assertNull(gate.observe(null, 1_200L, 1_200L, 42L))
        assertNull("nothing may be authorized after a null observation", gate.current(1_200L, 42L))
        // The next capture cannot be accepted on the revoked history.
        assertNull(gate.observe(pressed, 1_300L, 1_300L, 42L))
        assertNull(gate.current(1_300L, 42L))
        assertNotNull(gate.observe(pressed, 1_400L, 1_400L, 42L))
    }

    @Test
    fun ambiguitiesAreNotResolvedByCoordinatePreference() {
        // The recognizer is pure: the same bytes must deny regardless of frame geometry, and a
        // crop of a two-control frame must not become a positive by clipping one control.
        for (c in cases.filter { it.category == "ambiguity" }) {
            requireDenied(c)
        }
        // Positive control for the same assertion class: a single-control frame is positive.
        requirePositive(cases.single { it.id == "derived-translucent-relocated" })
    }

    @Test
    fun trueTranslucentControlsAndOpaqueControlsAreIndependentlyRecognized() {
        val group = cases.filter { it.category == "cross-path-positive" }
        assertEquals(3, group.size)
        for (c in group) requirePositive(c)
        for (id in listOf("cross-resting-a", "cross-resting-b")) {
            val c=cases.single { it.id==id }; val bytes=c.file.readBytes()
            val t=TranslucentAttackControl.find(c.width,c.height,bytes,AttackButtonPixels.scratchFor(c.width*c.height),null)
            assertTrue(!t.exhausted)
            assertEquals("actual translucent path must see the resting patch",1,t.points.size)
        }
    }

    @Test
    fun genuineRestingPatchAndSyntheticPressedAppearanceShareRealGateAuthority() {
        val resting=requirePositive(cases.single { it.id=="cross-resting-a" })
        val pressed=requirePositive(cases.single { it.id=="cross-pressed-a" })
        val gate=AttackButtonEvidenceGate(3000L)
        assertNull(gate.observe(resting,1000L,1000L,42L))
        assertNotNull(gate.observe(pressed,1100L,1100L,42L))
        assertNotNull(gate.observe(resting,1200L,1200L,42L))
        assertNull(gate.observe(null,1300L,1300L,42L))
        assertNull(gate.current(1300L,42L))
    }
}
