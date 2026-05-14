package com.metrolauncher.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.metrolauncher.R
import com.metrolauncher.model.Tile
import com.metrolauncher.model.TileKind
import com.metrolauncher.model.TileSize
import com.metrolauncher.util.FaviconCache
import com.metrolauncher.util.GridPacker
import com.metrolauncher.util.Prefs
import com.metrolauncher.util.TileStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Activity trasparente invocata quando l'utente condivide un URL da qualsiasi browser
 * verso Metro Launcher (tramite il tasto "Condividi" → "Aggiungi a Start").
 *
 * Non ha layout proprio — mostra solo un AlertDialog sopra il browser. L'utente
 * resta nel browser durante tutta l'interazione.
 *
 * Flusso:
 *  1. Riceve ACTION_SEND text/plain con l'URL in EXTRA_TEXT
 *  2. Mostra il dialog con nome pre-compilato (dal titolo della pagina o dal dominio)
 *  3. Al "Aggiungi": salva la tile nello storage e avvia il download favicon
 *  4. Si chiude — il browser torna in primo piano
 *
 * La prossima volta che l'utente apre Metro Launcher, la tile è presente.
 * Se Metro Launcher era già aperto in background, ricarica automaticamente le tile
 * in onResume.
 */
class ShareToStartActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Rende l'Activity trasparente — si vede il browser dietro
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val url = extractUrl(intent)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "URL non valido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val suggestedName = intent?.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: extractDomain(url)

        showAddDialog(url, suggestedName)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ----------------------------------------------------------------------

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        // A volte il browser manda "Titolo\nhttps://..." oppure solo l'URL
        return text.lines()
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?: text.trim().takeIf { it.startsWith("http") }
    }

    private fun extractDomain(url: String): String =
        runCatching {
            Uri.parse(url).host?.removePrefix("www.") ?: url
        }.getOrDefault(url)

    private fun showAddDialog(url: String, suggestedName: String) {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }

        // Mostra il dominio come sottotitolo
        val domainView = TextView(this).apply {
            text = extractDomain(url)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 13f
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        val nameEdit = EditText(this).apply {
            hint = "Nome del link"
            setText(suggestedName)
            setHintTextColor(0x66FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            background = null
            textSize = 18f
            selectAll()
        }
        layout.addView(domainView)
        layout.addView(nameEdit)

        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog)
            .setTitle("Aggiungi a Start")
            .setView(layout)
            .setPositiveButton("Aggiungi") { _, _ ->
                val name = nameEdit.text.toString().ifBlank { extractDomain(url) }
                addTileAndFetchFavicon(url, name)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()

        // Metti il focus sull'EditText e seleziona tutto per edit rapido
        nameEdit.requestFocus()
    }

    private fun addTileAndFetchFavicon(url: String, name: String) {
        val storage = TileStorage(this)
        val tiles = storage.load().toMutableList()
        val cols = Prefs.gridColumns(this)

        val newTile = Tile(
            id = UUID.randomUUID().toString(),
            kind = TileKind.WEB_LINK,
            webUrl = url,
            customLabel = name,
            size = TileSize.MEDIUM
        )
        // Trova una posizione libera
        val (r, c) = GridPacker.findFreeSlot(tiles, newTile.size, cols)
        newTile.row = r; newTile.col = c
        tiles.add(newTile)
        storage.save(tiles)

        // Feedback immediato all'utente
        Toast.makeText(this, "\"$name\" aggiunto a Start", Toast.LENGTH_SHORT).show()

        // Avvia il download favicon in background poi chiudi
        scope.launch {
            withContext(Dispatchers.IO) {
                FaviconCache.fetchAndSave(this@ShareToStartActivity, newTile.id, url)
            }
            // Notifica il launcher se è in esecuzione (vedi MainActivity.onResume)
            // Non forziamo l'apertura — l'utente resta nel browser
            finish()
        }
    }
}
