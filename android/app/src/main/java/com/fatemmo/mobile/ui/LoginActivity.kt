package com.fatemmo.mobile.ui

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fatemmo.mobile.BuildConfig
import com.fatemmo.mobile.R
import com.fatemmo.mobile.config.ServerConfig
import com.fatemmo.mobile.databinding.ActivityLoginBinding
import com.fatemmo.mobile.net.LoginClient
import com.fatemmo.mobile.net.LoginResult
import com.fatemmo.mobile.util.NativeBridge
import kotlinx.coroutines.launch

/**
 * Native login screen (Phase 1 — see docs/FATE_MMO_MOBILE_ROADMAP.md).
 *
 * On success this only *displays* the account id and char-server list; it does
 * not proceed to character selection. That hand-off is Phase 2 and must not be
 * faked here with placeholder character data (brief §35 — no fake features).
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
        binding.exitButton.setOnClickListener { confirmExit() }
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
    }

    private fun setBusy(busy: Boolean) {
        binding.loadingSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !busy
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
