package com.fatemmo.mobile.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fatemmo.mobile.BuildConfig
import com.fatemmo.mobile.R
import com.fatemmo.mobile.assets.AssetDownloadManager
import com.fatemmo.mobile.assets.AssetPack
import com.fatemmo.mobile.config.ServerConfig
import com.fatemmo.mobile.databinding.ActivityLoginBinding
import com.fatemmo.mobile.net.CharServerEntry
import com.fatemmo.mobile.net.LoginClient
import com.fatemmo.mobile.net.LoginResult
import com.fatemmo.mobile.util.NativeBridge
import kotlinx.coroutines.launch

/**
 * Native login screen (Phase 1 — see docs/FATE_MMO_MOBILE_ROADMAP.md).
 *
 * On success, hands off to [CharSelectActivity] (Phase 2) with the char-server
 * target and auth data (account id, login_id1/2, sex) from AC_ACCEPT_LOGIN.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FateMMO/LoginActivity"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var serverConfig: ServerConfig
    private val loginClient = LoginClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverConfig = ServerConfig.load(this, BuildConfig.SERVER_CONFIG_ASSET)

        // Proves the NDK/CMake toolchain actually links and loads on-device;
        // see native/src/native_lib.cpp and docs/FATE_MMO_MOBILE_ROADMAP.md Phase 1.
        Log.d(TAG, "native bridge check: ${NativeBridge.stubEngineVersion()}")

        binding.loginButton.setOnClickListener { attemptLogin() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.assetsButton.setOnClickListener { showAssetsDialog() }
        binding.exitButton.setOnClickListener { confirmExit() }
    }

    /**
     * Two-tier download choice (essential vs. full pack) against the
     * server-hosted manifest — see docs/FATE_MMO_MOBILE_ASSETS.md
     * §download-system and [AssetDownloadManager]. Purely additive: login
     * still works without ever opening this, falling back to whatever
     * assets are bundled in the APK (see MapActivity.showRealMap).
     */
    private fun showAssetsDialog() {
        val baseUrl = serverConfig.assetBaseUrl
        if (baseUrl == null) {
            binding.statusText.text = getString(R.string.assets_unavailable, serverConfig.environment)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_assets_title)
            .setItems(arrayOf(
                getString(R.string.assets_option_essential),
                getString(R.string.assets_option_full)
            )) { _, which ->
                lifecycleScope.launch { runDownload(baseUrl, essential = which == 0) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun runDownload(baseUrl: String, essential: Boolean) {
        val manager = AssetDownloadManager(this, baseUrl)
        setBusy(true)
        binding.statusText.text = getString(R.string.assets_fetching_manifest)

        val manifest = try {
            manager.fetchManifest()
        } catch (e: Exception) {
            setBusy(false)
            binding.statusText.text = getString(R.string.assets_manifest_error, e.message ?: e.toString())
            return
        }

        val pack: AssetPack = if (essential) manifest.essentialPack else manifest.fullPack
        val outcome = manager.downloadPack(pack) { progress ->
            val percent = if (progress.packBytesTotal > 0) {
                (progress.packBytesDone * 100 / progress.packBytesTotal).toInt()
            } else 100
            runOnUiThread {
                binding.statusText.text = getString(
                    R.string.assets_downloading,
                    progress.currentFile, progress.fileIndex, progress.fileCount, percent
                )
            }
        }

        setBusy(false)
        binding.statusText.text = when (outcome) {
            is AssetDownloadManager.Outcome.Success -> getString(R.string.assets_done, pack.files.size)
            is AssetDownloadManager.Outcome.Failed -> getString(R.string.assets_failed, outcome.message)
        }
    }

    private fun attemptLogin() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        if (username.isEmpty() || password.isEmpty()) {
            binding.statusText.text = getString(R.string.status_idle)
            return
        }

        setBusy(true)
        binding.statusText.text = getString(R.string.status_connecting, serverConfig.loginHost, serverConfig.loginPort)

        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.status_authenticating)
            val result = loginClient.login(
                host = serverConfig.loginHost,
                port = serverConfig.loginPort,
                username = username,
                password = password,
                clientVersion = serverConfig.clientVersion,
                clientType = serverConfig.clientType
            )
            setBusy(false)
            renderResult(result)
        }
    }

    private fun renderResult(result: LoginResult) {
        binding.statusText.text = when (result) {
            is LoginResult.Success -> getString(
                R.string.status_success,
                result.accountId,
                result.charServers.size
            )
            is LoginResult.Refused -> getString(R.string.status_refused, result.errorCode)
            is LoginResult.Banned -> getString(R.string.status_banned, result.resultCode)
            is LoginResult.ConnectionError -> getString(R.string.status_error, result.message)
        }

        if (result is LoginResult.Success) {
            goToCharSelect(result)
        }
    }

    /** Picks the char-server with the fewest connected users, per protocol doc §3.2. */
    private fun pickCharServer(servers: List<CharServerEntry>): CharServerEntry? =
        servers.minByOrNull { it.users }

    private fun goToCharSelect(result: LoginResult.Success) {
        val target = pickCharServer(result.charServers) ?: run {
            binding.statusText.text = getString(R.string.status_error, "No char-servers in AC_ACCEPT_LOGIN response")
            return
        }
        val intent = Intent(this, CharSelectActivity::class.java).apply {
            putExtra(CharSelectActivity.EXTRA_CHAR_HOST, target.ip)
            putExtra(CharSelectActivity.EXTRA_CHAR_PORT, target.port)
            putExtra(CharSelectActivity.EXTRA_ACCOUNT_ID, result.accountId)
            putExtra(CharSelectActivity.EXTRA_LOGIN_ID1, result.loginId1)
            putExtra(CharSelectActivity.EXTRA_LOGIN_ID2, result.loginId2)
            putExtra(CharSelectActivity.EXTRA_SEX, result.sex)
        }
        startActivity(intent)
    }

    private fun setBusy(busy: Boolean) {
        binding.loadingSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !busy
        binding.assetsButton.isEnabled = !busy
    }

    /** Dev-convenience override of the flavor-selected server target (brief §30). */
    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val hostInput = EditText(this).apply {
            hint = "Login host"
            setText(serverConfig.loginHost)
        }
        val portInput = EditText(this).apply {
            hint = "Login port"
            setText(serverConfig.loginPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        container.addView(hostInput)
        container.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_settings_title)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newPort = portInput.text.toString().toIntOrNull() ?: serverConfig.loginPort
                serverConfig = serverConfig.copy(
                    loginHost = hostInput.text.toString().trim(),
                    loginPort = newPort
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_exit_title)
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
