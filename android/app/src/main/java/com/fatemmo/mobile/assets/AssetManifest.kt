package com.fatemmo.mobile.assets

import org.json.JSONObject

/**
 * One file entry in the server-hosted asset manifest (see
 * tools/grf/out/manifest.json, uploaded to
 * `{assetBaseUrl}/manifest.json` — served today from
 * http://167.104.101.102:8080/fatemobile-assets/manifest.json, which is the
 * TCP-proxy in front of the real backend, never the backend directly; see
 * docs/FATE_MMO_MOBILE_ASSETS.md §download-system).
 */
data class AssetFileEntry(
    val path: String,
    val sha256: String,
    val size: Long
)

data class AssetPack(
    val description: String,
    val files: List<AssetFileEntry>
)

/**
 * Parsed form of manifest.json. `essentialPack` and `fullPack` are the two
 * choices surfaced in the login screen's download dialog, matching the
 * operator's original two-tier request. As of this build both packs contain
 * the same three files (prontera map + novice sprite) because that's all
 * the tools/grf pipeline has converted so far — this is not a fake
 * distinction, it's what's really on the server right now, and the UI says
 * so rather than implying "all" unlocks content that doesn't exist yet.
 */
data class AssetManifest(
    val version: Int,
    val essentialPack: AssetPack,
    val fullPack: AssetPack
) {
    companion object {
        fun parse(json: String): AssetManifest {
            val root = JSONObject(json)
            val packs = root.getJSONObject("packs")
            val essential = packs.getJSONObject("essential")
            val full = root.getJSONObject("full")
            return AssetManifest(
                version = root.optInt("manifestVersion", 1),
                essentialPack = AssetPack(
                    description = essential.optString("description", ""),
                    files = parseFiles(essential.getJSONArray("files"))
                ),
                fullPack = AssetPack(
                    description = full.optString("description", ""),
                    files = parseFiles(full.getJSONArray("files"))
                )
            )
        }

        private fun parseFiles(arr: org.json.JSONArray) = (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            AssetFileEntry(
                path = f.getString("path"),
                sha256 = f.getString("sha256"),
                size = f.getLong("size")
            )
        }
    }
}
