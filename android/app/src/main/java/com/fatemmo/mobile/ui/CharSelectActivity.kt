package com.fatemmo.mobile.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fatemmo.mobile.R
import com.fatemmo.mobile.databinding.ActivityCharSelectBinding
import com.fatemmo.mobile.databinding.ItemCharacterBinding
import com.fatemmo.mobile.net.CharCreateResult
import com.fatemmo.mobile.net.CharDeleteResult
import com.fatemmo.mobile.net.CharListResult
import com.fatemmo.mobile.net.CharSelectResult
import com.fatemmo.mobile.net.CharServerClient
import com.fatemmo.mobile.net.CharacterInfo
import kotlinx.coroutines.launch

/**
 * Character list/select/create/delete screen (Phase 2 —
 * docs/FATE_MMO_MOBILE_ROADMAP.md). Selecting a character hands off to
 * [MapActivity] (Phase 3) with the server's real map-redirect data.
 */
class CharSelectActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAR_HOST = "char_host"
        const val EXTRA_CHAR_PORT = "char_port"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_LOGIN_ID1 = "login_id1"
        const val EXTRA_LOGIN_ID2 = "login_id2"
        const val EXTRA_SEX = "sex"
    }

    private lateinit var binding: ActivityCharSelectBinding
    private val client = CharServerClient()
    private val characters = mutableListOf<CharacterInfo>()
    private var producibleSlots = 0
    private var maxSlots = 0
    private var accountSex = 0
    private var accountId = -1L
    private var loginId1 = 0L
    private var charServerHost = ""
    private var charServerPort = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCharSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host = intent.getStringExtra(EXTRA_CHAR_HOST)
        if (host.isNullOrEmpty()) {
            finish()
            return
        }
        val port = intent.getIntExtra(EXTRA_CHAR_PORT, 0)
        val loginId2 = intent.getLongExtra(EXTRA_LOGIN_ID2, 0L)
        charServerHost = host
        charServerPort = port
        accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
        loginId1 = intent.getLongExtra(EXTRA_LOGIN_ID1, 0L)
        accountSex = intent.getIntExtra(EXTRA_SEX, 0)

        binding.createButton.setOnClickListener { showCreateDialog() }
        binding.backButton.setOnClickListener {
            client.close()
            finish()
        }

        lifecycleScope.launch {
            val result = client.connect(host, port, accountId, loginId1, loginId2, accountSex)
            binding.loadingSpinner.visibility = View.GONE
            when (result) {
                is CharListResult.Success -> {
                    producibleSlots = result.producibleSlots
                    maxSlots = result.maxSlots
                    characters.clear()
                    characters.addAll(result.characters)
                    renderList()
                }
                is CharListResult.Refused ->
                    binding.statusText.text = getString(R.string.charselect_status_refused, result.errorCode)
                is CharListResult.ConnectionError ->
                    binding.statusText.text = getString(R.string.charselect_status_error, result.message)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.close()
    }

    private fun renderList() {
        binding.statusText.text = if (characters.isEmpty()) {
            getString(R.string.charselect_status_empty)
        } else {
            getString(R.string.charselect_status_ready, characters.size, producibleSlots)
        }

        binding.charListContainer.removeAllViews()
        for (character in characters) {
            val row = ItemCharacterBinding.inflate(layoutInflater, binding.charListContainer, false)
            row.nameText.text = character.name
            row.detailText.text = getString(
                R.string.charselect_character_detail,
                character.job,
                character.level,
                character.jobLevel
            )
            row.playButton.setOnClickListener { selectCharacter(character) }
            row.deleteButton.setOnClickListener { showDeleteDialog(character) }
            binding.charListContainer.addView(row.root)
        }
    }

    private fun selectCharacter(character: CharacterInfo) {
        lifecycleScope.launch {
            when (val result = client.selectCharacter(character.slot)) {
                is CharSelectResult.MapRedirect -> {
                    val intent = Intent(this@CharSelectActivity, MapActivity::class.java).apply {
                        putExtra(MapActivity.EXTRA_MAP_HOST, result.mapIp)
                        putExtra(MapActivity.EXTRA_MAP_PORT, result.mapPort)
                        putExtra(MapActivity.EXTRA_MAP_NAME, result.mapName)
                        putExtra(MapActivity.EXTRA_CHAR_HOST, charServerHost)
                        putExtra(MapActivity.EXTRA_CHAR_PORT, charServerPort)
                        putExtra(MapActivity.EXTRA_ACCOUNT_ID, accountId)
                        putExtra(MapActivity.EXTRA_CHAR_ID, result.charId)
                        putExtra(MapActivity.EXTRA_LOGIN_ID1, loginId1)
                        putExtra(MapActivity.EXTRA_SEX, accountSex)
                    }
                    startActivity(intent)
                }
                is CharSelectResult.Refused ->
                    binding.statusText.text = getString(R.string.charselect_select_refused, result.errorCode)
                is CharSelectResult.NoMapServerAvailable ->
                    binding.statusText.text = getString(R.string.charselect_select_no_map_server)
                is CharSelectResult.ConnectionError ->
                    binding.statusText.text = getString(R.string.charselect_select_error, result.message)
            }
        }
    }

    private fun showCreateDialog() {
        val nameInput = EditText(this).apply {
            hint = getString(R.string.create_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_create_title)
            .setView(nameInput)
            .setPositiveButton(R.string.create_button) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) createCharacter(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createCharacter(name: String) {
        val slot = nextAvailableSlot()
        lifecycleScope.launch {
            // hairColor/hairStyle/startJob left at defaults (novice) — the server
            // hardcodes starting stats regardless, see protocol doc §4.5. A fuller
            // customization UI is a later-phase polish item, not needed to prove
            // the create flow works end-to-end against the real server.
            when (val result = client.createCharacter(name, slot, hairColor = 0, hairStyle = 0, startJob = 0, sex = accountSex)) {
                is CharCreateResult.Success -> {
                    characters.add(result.character)
                    renderList()
                }
                is CharCreateResult.Refused ->
                    binding.statusText.text = getString(R.string.charselect_create_refused, result.errorCode)
                is CharCreateResult.ConnectionError ->
                    binding.statusText.text = getString(R.string.charselect_create_error, result.message)
            }
        }
    }

    private fun nextAvailableSlot(): Int {
        val used = characters.map { it.slot }.toSet()
        for (slot in 0 until maxSlots) {
            if (slot !in used) return slot
        }
        return characters.size
    }

    private fun showDeleteDialog(character: CharacterInfo) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val helpText = TextView(this).apply {
            text = getString(R.string.delete_confirm_help)
            textSize = 12f
        }
        val codeInput = EditText(this).apply {
            hint = getString(R.string.delete_confirm_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(helpText)
        container.addView(codeInput)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title, character.name))
            .setView(container)
            .setPositiveButton(R.string.delete_button) { _, _ ->
                deleteCharacter(character, codeInput.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCharacter(character: CharacterInfo, confirmationCode: String) {
        lifecycleScope.launch {
            when (val result = client.deleteCharacter(character.charId, confirmationCode)) {
                is CharDeleteResult.Success -> {
                    characters.remove(character)
                    renderList()
                }
                is CharDeleteResult.Refused ->
                    binding.statusText.text = getString(R.string.charselect_delete_refused, result.errorCode)
                is CharDeleteResult.ConnectionError ->
                    binding.statusText.text = getString(R.string.charselect_delete_error, result.message)
            }
        }
    }
}
