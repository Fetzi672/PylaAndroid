package com.pyla.ai.engine

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A bounded authorization to read/write the learned attack-button calibration for one capture
 * geometry epoch. A lease grants no input authority by itself: callers still enforce gameplay,
 * STOP, foreground, capture freshness and guarded dispatch.
 */
data class AttackCalibrationLease(
    val revision: Long,
    val width: Int,
    val height: Int,
    val geometry: Long,
)

/**
 * Durable single-record storage for the learned attack-button location. Injected so the pure
 * core can be exercised deterministically without Android or disk.
 */
interface AttackCalibrationStorage {
    fun read(): String?
    fun write(value: String)

    /** @return true when a previously stored record was actually removed. */
    fun remove(): Boolean
}

/**
 * Pure, clock-free persistent calibration of the stationary attack control.
 *
 * The record is bound to exact capture width/height, never scaled. Its recorded geometry epoch
 * is provenance only: process-local epoch numbers are not durable geometry identities. New live
 * epochs revoke pending leases; a fresh current lease can reuse a record of identical dimensions.
 *
 * Learning requires two distinct, fresh template matches of the same control, using the same
 * 3000 ms freshness window and center/radius consistency as [AttackButtonEvidenceGate]. A null,
 * invalid, stale, out-of-order or duplicated observation never confirms a calibration.
 */
class SavedAttackButtonCalibrationCore(
    private val storage: AttackCalibrationStorage,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {

    companion object {
        const val DEFAULT_MAX_AGE_MS = 3_000L
        const val SCHEMA = 1
        const val TEMPLATE_ID = "attack_crosshair_v1"
        private const val PREFIX = "pyla.attack.calibration"

        private fun bits(value: Float): Int = java.lang.Float.floatToIntBits(value)
    }

    /** Stored record. Kept internal so no pixel coordinate leaks through a public surface. */
    private data class Record(
        val width: Int,
        val height: Int,
        val geometry: Long,
        val x: Float,
        val y: Float,
        val radius: Float,
    ) {
        fun encode(): String = buildString {
            append(PREFIX).append('/').append(SCHEMA)
            append(";tpl=").append(TEMPLATE_ID)
            append(";w=").append(width)
            append(";h=").append(height)
            append(";g=").append(geometry)
            append(";x=").append(bits(x))
            append(";y=").append(bits(y))
            append(";r=").append(bits(radius))
        }
    }

    private data class Pending(val point: AttackButtonPoint, val capturedAtMs: Long, val width: Int, val height: Int, val geometry: Long)

    private var revision = 0L
    private var record: Record? = null
    private var pending: Pending? = null
    private var loaded = false

    private var activeWidth = 0
    private var activeHeight = 0
    private var activeGeometry = 0L
    private var active = false

    /** Current calibration epoch. Any [begin] with changed dimensions/geometry bumps it. */
    @Synchronized
    fun revision(): Long {
        load()
        return revision
    }

    /**
     * Opens (or reuses) the calibration epoch for the given capture geometry. Repeat calls with
     * identical dimensions and geometry keep the same revision, so a per-frame call cannot
     * invalidate in-flight learning or the caller's own lease.
     */
    @Synchronized
    fun begin(width: Int, height: Int, geometry: Long): AttackCalibrationLease {
        load()
        if (width > 0 && height > 0 && width.toLong() * height <= 16_777_216L && geometry >= 0L) {
            if (!active || width != activeWidth || height != activeHeight || geometry != activeGeometry) {
                revision++
                pending = null
                active = true
                activeWidth = width
                activeHeight = height
                activeGeometry = geometry
            }
        }
        return AttackCalibrationLease(revision, width, height, geometry)
    }

    /** The learned location for exactly this lease's dimensions, or null. Never scales. */
    @Synchronized
    fun saved(lease: AttackCalibrationLease): AttackButtonPoint? {
        load()
        if (!leaseCurrent(lease)) return null
        val rec = record ?: return null
        if (!recordUsable(rec)) return null
        if (rec.width != lease.width || rec.height != lease.height) return null
        return AttackButtonPoint(rec.x, rec.y, rec.radius)
    }

    /** Lease validity is independent of having a saved point: empty stores must be able to learn. */
    @Synchronized
    fun current(lease: AttackCalibrationLease): Boolean {
        load()
        return leaseCurrent(lease)
    }

    /**
     * Feeds one template observation. Returns the learned point only when this observation
     * completes the two-frame confirmation. A null point resets the pending pair.
     */
    @Synchronized
    fun observeTemplate(
        lease: AttackCalibrationLease,
        point: AttackButtonPoint?,
        capturedAtMs: Long,
        nowMs: Long,
    ): AttackButtonPoint? {
        load()
        // A stale lease (cleared, reset, or superseded by another geometry/size) cannot learn.
        if (!leaseCurrent(lease)) return null
        val p = point
        if (p == null || !validPoint(p, lease.width, lease.height) || !fresh(capturedAtMs, nowMs)) {
            pending = null
            return null
        }
        val prior = pending
        if (prior == null || prior.width != lease.width || prior.height != lease.height ||
            prior.geometry != lease.geometry || !fresh(prior.capturedAtMs, nowMs)
        ) {
            pending = Pending(p, capturedAtMs, lease.width, lease.height, lease.geometry)
            return null
        }
        if (capturedAtMs < prior.capturedAtMs) {
            pending = Pending(p, capturedAtMs, lease.width, lease.height, lease.geometry)
            return null
        }
        // The same frame twice is one observation, never a confirmation.
        if (capturedAtMs == prior.capturedAtMs) return saved(lease)
        if (!consistent(prior.point, p)) {
            pending = Pending(p, capturedAtMs, lease.width, lease.height, lease.geometry)
            return null
        }
        val learned = Record(lease.width, lease.height, lease.geometry, p.x, p.y, p.radius)
        if (!recordUsable(learned)) {
            pending = null
            return null
        }
        pending = Pending(p, capturedAtMs, lease.width, lease.height, lease.geometry)
        storage.write(learned.encode())
        record = learned
        return AttackButtonPoint(learned.x, learned.y, learned.radius)
    }

    /**
     * Revokes outstanding leases and discards the pending pair while retaining the durable
     * learned record. Used when the caller observes an ambiguous frame or STOP.
     */
    @Synchronized
    fun resetObservations() {
        load()
        pending = null
        revision++
    }

    /**
     * Revokes outstanding leases and forgets the learned point in memory and in storage.
     * @return true when a saved record existed and was removed; false when there was none.
     *         This reports state removal only, never physical fsync.
     */
    @Synchronized
    fun clear(): Boolean {
        load()
        pending = null
        revision++
        val hadRecord = record != null
        record = null
        val removedFromStorage = storage.remove()
        return hadRecord || removedFromStorage
    }

    @Synchronized
    fun hasSaved(): Boolean {
        load()
        return record?.let { recordUsable(it) } == true
    }

    private fun leaseCurrent(lease: AttackCalibrationLease): Boolean =
        active && lease.revision == revision &&
            lease.width == activeWidth && lease.height == activeHeight && lease.geometry == activeGeometry

    private fun load() {
        if (loaded) return
        val raw = storage.read()
        loaded = true
        if (raw == null) return
        val decoded = decode(raw)
        if (decoded == null || !recordUsable(decoded)) {
            // Corrupt, unsupported, non-finite, out-of-bounds or old-schema records are rejected
            // and never become a usable calibration.
            record = null
            storage.remove()
            return
        }
        record = decoded
    }

    private fun recordUsable(rec: Record): Boolean =
        rec.width > 0 && rec.height > 0 && rec.geometry >= 0L &&
            validPoint(AttackButtonPoint(rec.x, rec.y, rec.radius), rec.width, rec.height)

    /** Finite, positive radius that fits entirely inside the capture and sits in its lower-right. */
    private fun validPoint(p: AttackButtonPoint, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_777_216L) return false
        if (!p.x.isFinite() || !p.y.isFinite() || !p.radius.isFinite()) return false
        if (p.radius <= 0f) return false
        if (p.x - p.radius < 0f || p.y - p.radius < 0f) return false
        if (p.x + p.radius > width.toFloat() || p.y + p.radius > height.toFloat()) return false
        return p.x >= width / 2f && p.y >= height / 2f
    }

    private fun consistent(a: AttackButtonPoint, b: AttackButtonPoint): Boolean =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= max(2.0, min(a.radius, b.radius) * 0.10) &&
            max(a.radius, b.radius) <= min(a.radius, b.radius) * 1.2f

    private fun fresh(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs > 0L && capturedAtMs <= nowMs && nowMs - capturedAtMs <= maxAgeMs

    private fun decode(raw: String): Record? {
        val parts = raw.trim().split(';')
        if (parts.size != 8) return null
        if (parts[0] != "$PREFIX/$SCHEMA") return null
        if (parts[1] != "tpl=$TEMPLATE_ID") return null
        fun intField(index: Int, key: String): Int? {
            val field = parts[index]
            if (!field.startsWith("$key=")) return null
            return field.removePrefix("$key=").toIntOrNull()
        }
        val width = intField(2, "w") ?: return null
        val height = intField(3, "h") ?: return null
        val geometryField = parts[4]
        if (!geometryField.startsWith("g=")) return null
        val geometry = geometryField.removePrefix("g=").toLongOrNull() ?: return null
        val x = intField(5, "x")?.let { java.lang.Float.intBitsToFloat(it) } ?: return null
        val y = intField(6, "y")?.let { java.lang.Float.intBitsToFloat(it) } ?: return null
        val radius = intField(7, "r")?.let { java.lang.Float.intBitsToFloat(it) } ?: return null
        return Record(width, height, geometry, x, y, radius)
    }
}
