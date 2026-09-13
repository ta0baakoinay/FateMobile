package com.fatemmo.mobile.config

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/**
 * Login-server connection target, loaded from the environment-specific JSON
 * asset selected by the active product flavor (see android/app/build.gradle.kts
 * and docs/FATE_MMO_MOBILE_ARCHITECTURE.md §8). Only host/port ever ship in the
 * client — never DB or admin credentials.
 */
data class ServerConfig(
    val environment: String,
    val loginHost: String,
    val loginPort: Int,
    val clientVersion: Long,
    val clientType: Int,
    /**
     * Base URL for the two-tier asset download system (see
     * docs/FATE_MMO_MOBILE_ASSETS.md §download-system). Null in configs where
     * no asset server has been stood up yet (development/staging) — callers
     * must treat that as "downloads unavailable in this environment", not
     * fall back to a guessed URL.
     */
    val assetBaseUrl: String?
) {
    companion object {
        /**
         * @param assetName one of the BuildConfig.SERVER_CONFIG_ASSET values
         * (server_config_development.json / _staging.json / _production.json).
         */
        fun load(context: Context, assetName: String): ServerConfig {
            val json = try {
                context.assets.open(assetName).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                throw IllegalStateException("Missing server config asset: $assetName", e)
            }
            val obj = JSONObject(json)
            val login = obj.getJSONObject("login")
            return ServerConfig(
                environment = obj.optString("environment", "unknown"),
                loginHost = login.getString("host"),
                loginPort = login.getInt("port"),
                // Read by the server into PACKET_CA_LOGIN.version but not enforced by
                // logclif_parse_reqauth_raw() in the inspected FateRO source — included
                // for wire compliance with the struct layout, not because the server
                // currently rejects on it. See docs/FATE_MMO_MOBILE_PROTOCOL.md §3.1.
                // Default of 55 is the real value from data/clientinfo.xml inside the
                // actual Fate.grf client package — NOT the server's PACKETVER build
                // date (20250716), which is an unrelated number for a different field.
                clientVersion = obj.optLong("clientVersion", 55L),
                clientType = obj.optInt("clientType", 0),
                assetBaseUrl = if (obj.has("assetBaseUrl")) obj.getString("assetBaseUrl") else null
            )
        }
    }
}
