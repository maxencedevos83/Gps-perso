package fr.maxence.gpsperso

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.json.JSONObject
import kotlin.math.cos

/**
 * Cartes hors ligne : les tuiles OpenFreeMap (zoom 5 à 14, le maximum servi) sont gardées
 * dans la base MapLibre du téléphone et réutilisées sans réseau.
 */
object Offline {
    const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
    private const val MIN_ZOOM = 5.0
    private const val MAX_ZOOM = 14.0

    class Zone(val name: String, val regions: List<OfflineRegion>) {
        var bytes = 0L
        var complete = true
    }

    fun boundsAround(lat: Double, lon: Double, km: Double): LatLngBounds {
        val dLat = km / 111.0
        val dLon = km / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return LatLngBounds.Builder()
            .include(LatLng(lat - dLat, lon - dLon))
            .include(LatLng(lat + dLat, lon + dLon))
            .build()
    }

    /** Morceaux d'environ 25 km le long du trajet, avec 4 km de marge de chaque côté. */
    fun routeBounds(r: Route): List<LatLngBounds> {
        val list = ArrayList<LatLngBounds>()
        var start = 0
        while (start < r.points.size - 1) {
            var end = start
            while (end < r.points.size - 1 && r.cumulative[end] - r.cumulative[start] < 25_000) end++
            var s = Double.MAX_VALUE; var w = Double.MAX_VALUE; var n = -Double.MAX_VALUE; var e = -Double.MAX_VALUE
            for (i in start..end) {
                val p = r.points[i]
                s = minOf(s, p[0]); n = maxOf(n, p[0]); w = minOf(w, p[1]); e = maxOf(e, p[1])
            }
            val dLat = 4 / 111.0
            val dLon = 4 / (111.0 * cos(Math.toRadians(s)).coerceAtLeast(0.2))
            list += LatLngBounds.Builder().include(LatLng(s - dLat, w - dLon)).include(LatLng(n + dLat, e + dLon)).build()
            start = end
        }
        return list
    }

    /** Télécharge une zone (un ou plusieurs rectangles) ; progress(fait, total) sur le fil principal. */
    fun download(
        ctx: Context, name: String, boxes: List<LatLngBounds>,
        progress: (Int, Int) -> Unit, done: (String?) -> Unit,
    ) {
        val manager = OfflineManager.getInstance(ctx)
        val ratio = ctx.resources.displayMetrics.density
        val meta = JSONObject().put("name", name).put("at", System.currentTimeMillis()).toString().toByteArray()
        var finished = 0
        var failed: String? = null
        fun next(i: Int) {
            if (i >= boxes.size) { done(failed); return }
            val def = OfflineTilePyramidRegionDefinition(STYLE, boxes[i], MIN_ZOOM, MAX_ZOOM, ratio)
            manager.createOfflineRegion(def, meta, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                offlineRegion.setObserver(null)
                                finished++
                                progress(finished, boxes.size)
                                next(i + 1)
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            failed = error.message
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {}
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    failed = error
                    next(i + 1)
                }
            })
        }
        progress(0, boxes.size)
        next(0)
    }

    fun list(ctx: Context, done: (List<Zone>) -> Unit) {
        OfflineManager.getInstance(ctx).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions?.toList().orEmpty()
                val zones = regions.groupBy { nameOf(it) }.map { (n, r) -> Zone(n, r) }
                if (zones.isEmpty()) { done(zones); return }
                var pending = regions.size
                val byRegion = zones.flatMap { z -> z.regions.map { it to z } }
                byRegion.forEach { (region, zone) ->
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            if (status != null) {
                                zone.bytes += status.completedResourceSize
                                if (!status.isComplete) zone.complete = false
                            }
                            if (--pending == 0) done(zones)
                        }
                        override fun onError(error: String?) {
                            if (--pending == 0) done(zones)
                        }
                    })
                }
            }

            override fun onError(error: String) = done(emptyList())
        })
    }

    fun delete(zone: Zone, done: () -> Unit) {
        var pending = zone.regions.size
        if (pending == 0) { done(); return }
        zone.regions.forEach {
            it.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { if (--pending == 0) done() }
                override fun onError(error: String) { if (--pending == 0) done() }
            })
        }
    }

    private fun nameOf(r: OfflineRegion): String = try {
        JSONObject(String(r.metadata)).optString("name", "Zone")
    } catch (e: Exception) {
        "Zone"
    }
}
