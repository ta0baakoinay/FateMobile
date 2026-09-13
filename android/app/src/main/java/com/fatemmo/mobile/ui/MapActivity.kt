package com.fatemmo.mobile.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fatemmo.mobile.BuildConfig
import com.fatemmo.mobile.R
import com.fatemmo.mobile.assets.AssetDownloadManager
import com.fatemmo.mobile.config.ServerConfig
import com.fatemmo.mobile.databinding.ActivityMapBinding
import com.fatemmo.mobile.net.MapEnterResult
import com.fatemmo.mobile.net.MapServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Map screen (Phase 3 — docs/FATE_MMO_MOBILE_ROADMAP.md). Proves the
 * `CZ_ENTER` handshake against the real map-server, then — for the one map
 * this build has bundled real converted assets for (prontera, see
 * docs/FATE_MMO_MOBILE_ASSETS.md) — shows the real ground image, real
 * character sprite, and real GAT-based wall collision, with the character
 * placed at the server-confirmed spawn position.
 *
 * Movement in [GameMapView][com.fatemmo.mobile.world.GameMapView] is
 * client-local only: it does not send anything to the map-server. See that
 * class's doc and docs/FATE_MMO_MOBILE_PROTOCOL.md §5.6 for exactly why
 * (sending CZ_NOTIFY_ACTORINIT would open a flood of server->client
 * gameplay packets this client can't yet safely parse). For any other map
 * name, this falls back to the debug-only panel — no fake map is drawn for
 * assets that don't actually exist yet.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAP_HOST = "map_host"
        const val EXTRA_MAP_PORT = "map_port"
        const val EXTRA_MAP_NAME = "map_name"
        const val EXTRA_CHAR_HOST = "char_host"
        const val EXTRA_CHAR_PORT = "char_port"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_CHAR_ID = "char_id"
        const val EXTRA_LOGIN_ID1 = "login_id1"
        const val EXTRA_SEX = "sex"

        // This build only ships converted real assets for prontera — see
        // docs/FATE_MMO_MOBILE_ASSETS.md. Extending to other maps means
        // running the same tools/grf pipeline against that map's .gnd/.gat.
        private const val BUNDLED_MAP_NAME = "prontera"
        private const val BUNDLED_MAP_GROUND_ASSET = "maps/prontera/ground.jpg"
        private const val BUNDLED_MAP_GAT_ASSET = "maps/prontera/prontera.gat"

        // Same relative path used by tools/grf/out/manifest.json and the
        // server-hosted asset pack — see AssetDownloadManager. A downloaded
        // copy under filesDir/assets/... takes priority over the bundled
        // APK copy when both exist, since the download is how this build
        // gets updated art without shipping a new APK.
        private const val SPRITE_ASSET_PATH = "sprites/novice_male.png"
    }

    private lateinit var binding: ActivityMapBinding
    private val client = MapServerClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mapHost = intent.getStringExtra(EXTRA_MAP_HOST)
        if (mapHost.isNullOrEmpty()) {
            finish()
            return
        }
        val mapPort = intent.getIntExtra(EXTRA_MAP_PORT, 0)
        val mapName = intent.getStringExtra(EXTRA_MAP_NAME).orEmpty()
        val charHost = intent.getStringExtra(EXTRA_CHAR_HOST).orEmpty()
        val charPort = intent.getIntExtra(EXTRA_CHAR_PORT, 0)
        val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
        val charId = intent.getLongExtra(EXTRA_CHAR_ID, -1L)
        val loginId1 = intent.getLongExtra(EXTRA_LOGIN_ID1, 0L)
        val sex = intent.getIntExtra(EXTRA_SEX, 0)

        binding.backButton.setOnClickListener {
            client.close()
            finish()
        }

        lifecycleScope.launch {
            val result = client.connect(mapHost, mapPort, accountId, charId, loginId1, sex)
            when (result) {
                is MapEnterResult.Success -> {
                    binding.statusText.text = getString(R.string.map_status_success)
                    binding.detailText.text = getString(
                        R.string.map_detail,
                        mapName,
                        charHost, charPort,
                        mapHost, mapPort,
                        accountId,
                        charId,
                        result.x, result.y,
                        result.dir,
                        result.startTime
                    )
                    if (mapName.startsWith(BUNDLED_MAP_NAME, ignoreCase = true)) {
                        showRealMap(result.x, result.y)
                    }
                }
                is MapEnterResult.Refused ->
                    binding.statusText.text = getString(R.string.map_status_refused, result.errorCode)
                is MapEnterResult.Banned ->
                    binding.statusText.text = getString(R.string.map_status_banned, result.errorCode)
                is MapEnterResult.ConnectionError ->
                    binding.statusText.text = getString(R.string.map_status_error, result.message)
            }
        }
    }

    private suspend fun showRealMap(spawnX: Int, spawnY: Int) {
        val serverConfig = ServerConfig.load(this, BuildConfig.SERVER_CONFIG_ASSET)
        val downloadManager = serverConfig.assetBaseUrl?.let { AssetDownloadManager(this, it) }

        val (groundBytes, gatBytes) = withContext(Dispatchers.IO) {
            val ground = downloadManager?.localFile(BUNDLED_MAP_GROUND_ASSET)?.takeIf { it.exists() }
                ?.let { it.readBytes() }
                ?: assets.open(BUNDLED_MAP_GROUND_ASSET).use { it.readBytes() }
            val gat = downloadManager?.localFile(BUNDLED_MAP_GAT_ASSET)?.takeIf { it.exists() }
                ?.let { it.readBytes() }
                ?: assets.open(BUNDLED_MAP_GAT_ASSET).use { it.readBytes() }
            ground to gat
        }
        binding.gameMapView.visibility = android.view.View.VISIBLE
        binding.gameMapView.loadMap(groundBytes, gatBytes, spawnX, spawnY)

        val sprite = withContext(Dispatchers.IO) {
            val downloaded = downloadManager?.localFile(SPRITE_ASSET_PATH)?.takeIf { it.exists() }
            if (downloaded != null) {
                BitmapFactory.decodeFile(downloaded.absolutePath)
            } else {
                BitmapFactory.decodeResource(resources, R.drawable.sprite_novice_male)
            }
        }
        binding.gameMapView.setSprite(sprite)
    }

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }
}
