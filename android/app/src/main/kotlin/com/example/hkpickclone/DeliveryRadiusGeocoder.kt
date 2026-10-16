package com.example.hkpickclone

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Geocodes saved pin addresses and order drop-off text off the accessibility thread. */
object DeliveryRadiusGeocoder {
    data class Point(
        val latitude: Double,
        val longitude: Double,
        val label: String
    )

    enum class LookupState { FOUND, PENDING, NOT_FOUND }

    data class Lookup(val state: LookupState, val point: Point? = null)

    private data class CacheEntry(val point: Point?, val expiresAt: Long)

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {}
    private val pendingCallbacks = mutableMapOf<String, MutableList<(Point?) -> Unit>>()
    private val executor = ThreadPoolExecutor(
        2,
        2,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Resolve a user-entered pin address. Callback is always delivered on the main thread. */
    fun resolvePinAddress(context: Context, address: String, callback: (Point?) -> Unit) {
        resolve(context, address, callback)
    }

    /** Returns cached coordinates immediately; otherwise starts one background lookup. */
    fun lookupOrderAddress(context: Context, address: String): Lookup {
        val key = cacheKey(address) ?: return Lookup(LookupState.NOT_FOUND)
        synchronized(lock) {
            val entry = cache[key]
            if (entry != null && entry.expiresAt > System.currentTimeMillis()) {
                return if (entry.point != null) Lookup(LookupState.FOUND, entry.point)
                else Lookup(LookupState.NOT_FOUND)
            }
            if (entry != null) cache.remove(key)
        }
        resolve(context, address) { }
        return Lookup(LookupState.PENDING)
    }

    fun distanceKm(first: Point, second: Point): Double {
        val earthRadiusKm = 6371.0088
        val firstLat = Math.toRadians(first.latitude)
        val secondLat = Math.toRadians(second.latitude)
        val deltaLat = secondLat - firstLat
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
            Math.cos(firstLat) * Math.cos(secondLat) *
            Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun resolve(context: Context, address: String, callback: (Point?) -> Unit) {
        val key = cacheKey(address)
        if (key == null) {
            mainHandler.post { callback(null) }
            return
        }

        synchronized(lock) {
            val cached = cache[key]
            if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
                mainHandler.post { callback(cached.point) }
                return
            }
            if (cached != null) cache.remove(key)
            val waiting = pendingCallbacks[key]
            if (waiting != null) {
                waiting.add(callback)
                return
            }
            pendingCallbacks[key] = mutableListOf(callback)
        }

        try {
            executor.execute {
                val result = geocode(context.applicationContext, key)
                val callbacks: List<(Point?) -> Unit>
                synchronized(lock) {
                    val ttl = if (result == null) TimeUnit.MINUTES.toMillis(5) else TimeUnit.HOURS.toMillis(24)
                    cache[key] = CacheEntry(result, System.currentTimeMillis() + ttl)
                    while (cache.size > 256) cache.remove(cache.entries.iterator().next().key)
                    callbacks = pendingCallbacks.remove(key)?.toList().orEmpty()
                }
                mainHandler.post { callbacks.forEach { it(result) } }
            }
        } catch (_: RuntimeException) {
            val callbacks: List<(Point?) -> Unit>
            synchronized(lock) { callbacks = pendingCallbacks.remove(key)?.toList().orEmpty() }
            mainHandler.post { callbacks.forEach { it(null) } }
        }
    }

    @Suppress("DEPRECATION")
    private fun geocode(context: Context, key: String): Point? {
        return try {
            val query = if (key.contains("viet nam") || key.contains("vietnam")) key else "$key, Việt Nam"
            val geocoder = Geocoder(context, Locale("vi", "VN"))
            val addresses: List<Address> = geocoder.getFromLocationName(query, 3).orEmpty()
            val best = addresses.firstOrNull { it.hasLatitude() && it.hasLongitude() } ?: return null
            if (!best.latitude.isFinite() || !best.longitude.isFinite()) return null
            val label = (0..best.maxAddressLineIndex)
                .mapNotNull { index -> best.getAddressLine(index)?.trim()?.takeIf { it.isNotEmpty() } }
                .joinToString(", ")
            Point(best.latitude, best.longitude, label.ifBlank { query })
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(address: String): String? {
        val cleaned = address
            .replace(Regex("(?i)điểm\\s*(kết\\s*thúc|giao|đến)\\s*:?"), " ")
            .replace(Regex("[*•…]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.length >= 5 }?.lowercase(Locale.ROOT)
    }
}
