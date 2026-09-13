package com.fatemmo.mobile.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fatemmo.mobile.R
import com.fatemmo.mobile.databinding.ActivityMapBinding
import com.fatemmo.mobile.net.MapEnterResult
import com.fatemmo.mobile.net.MapServerClient
import kotlinx.coroutines.launch

/**
 * Map-server connect debug screen (Phase 3 —
 * docs/FATE_MMO_MOBILE_ROADMAP.md). Proves the `CZ_ENTER` handshake against
 * the real map-server and displays the server-confirmed spawn position.
 *
 * This is deliberately NOT a game screen: no rendering, no movement, no
 * entity system exist yet (that's later-phase work per the roadmap and
 * protocol doc §5.6). Building a fake map view here would violate the
 * project's no-fake-features rule, so this stays a debug/status screen
 * (which happens to satisfy brief §37's debug-overlay requirement) until
 * there's a real renderer to replace it with.
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

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }
}
