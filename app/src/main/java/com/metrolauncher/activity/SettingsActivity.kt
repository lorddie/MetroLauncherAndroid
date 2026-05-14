package com.metrolauncher.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.metrolauncher.R
import com.metrolauncher.util.Prefs
import com.metrolauncher.util.TileStorage
import com.metrolauncher.service.NotificationListener

/**
 * Settings Screen. Two main sections:
 *
 *   APPEARANCE
 *     - Custom wallpaper (image picker via ACTION_OPEN_DOCUMENT, persistent permission)
 *     - Reset system wallpaper
 *     - Grid columns: 4 (default, Windows Phone) or 6 ("compact" mode)
 *     - Tile font size: small / normal / large
 *     - Drawer font size: small / normal / large
 *
 *   BEHAVIOR
 *     - Lock rotation in portrait (default on)
 *     - Notification access (shortcut to system settings)
 *
 *   ADVANCED
 *     - Reset Start (clears all tiles)
 *
 * Preferences are persisted in the same SharedPreferences used by Prefs.kt 
 * (called "metro_launcher_prefs") to avoid maintaining two separate prefs files.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = getString(R.string.settings)
            setDisplayHomeAsUpEnabled(true)
        }
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class SettingsFragment : PreferenceFragmentCompat() {

        // Image picker for custom wallpaper
        private val pickWallpaper = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                Prefs.setString(requireContext(), Prefs.KEY_WALLPAPER_URI, uri.toString())
                updateWallpaperSummary()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Essential: use the same prefs file as the rest of the app
            preferenceManager.sharedPreferencesName = "metro_launcher_prefs"
            preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE
            try {
                setPreferencesFromResource(R.xml.settings_prefs, rootKey)
            } catch (t: Throwable) {
                android.util.Log.e("MetroLauncher", "setPreferencesFromResource failed", t)
                return
            }

            // --- Wallpaper
            findPreference<Preference>("pick_wallpaper")?.setOnPreferenceClickListener {
                pickWallpaper.launch(arrayOf("image/*"))
                true
            }
            findPreference<Preference>("reset_wallpaper")?.setOnPreferenceClickListener {
                Prefs.setString(requireContext(), Prefs.KEY_WALLPAPER_URI, null)
                updateWallpaperSummary()
                true
            }
            updateWallpaperSummary()

            // --- Grid (ListPreference with distinct key "grid_columns_choice";
            //     we save the Int value ourselves in the "grid_columns" key used 
            //     by the rest of the app, avoiding ClassCastException on the next getInt()).
            findPreference<ListPreference>("grid_columns_choice")?.let { list ->
                val current = Prefs.int(requireContext(), Prefs.KEY_GRID_COLUMNS,
                                        Prefs.DEFAULT_GRID_COLUMNS)
                list.value = current.toString()
                list.summary = "$current columns"
                list.setOnPreferenceChangeListener { _, newVal ->
                    val i = (newVal as? String)?.toIntOrNull() ?: Prefs.DEFAULT_GRID_COLUMNS
                    Prefs.setInt(requireContext(), Prefs.KEY_GRID_COLUMNS, i)
                    list.summary = "$i columns"
                    true
                }
            }

            // --- Tile font scale
            findPreference<ListPreference>(Prefs.KEY_TILE_FONT_SCALE)?.let { list ->
                list.value = Prefs.string(requireContext(), Prefs.KEY_TILE_FONT_SCALE,
                                          Prefs.DEFAULT_FONT_SCALE)
            }

            // --- Drawer font scale
            findPreference<ListPreference>(Prefs.KEY_DRAWER_FONT_SCALE)?.let { list ->
                list.value = Prefs.string(requireContext(), Prefs.KEY_DRAWER_FONT_SCALE,
                                          Prefs.DEFAULT_FONT_SCALE)
            }

            // --- Rotation lock
            findPreference<SwitchPreferenceCompat>(Prefs.KEY_LOCK_ROTATION)?.isChecked =
                Prefs.bool(requireContext(), Prefs.KEY_LOCK_ROTATION, default = true)

            // --- Tile opacity (SeekBarPreference saves as Int, safe)
            findPreference<androidx.preference.SeekBarPreference>(Prefs.KEY_TILE_OPACITY)?.apply {
                value = Prefs.int(requireContext(), Prefs.KEY_TILE_OPACITY, Prefs.DEFAULT_TILE_OPACITY)
            }

            // --- Global color (ListPreference with key "global_color_choice",
            //     we save the Int in KEY_GLOBAL_COLOR_INDEX to avoid ClassCastException)
            //     Value -1 (Prefs.CUSTOM_COLOR_SENTINEL) = "Custom...": opens an RGB 
            //     dialog and saves the free color in KEY_GLOBAL_COLOR_CUSTOM.
            findPreference<ListPreference>("global_color_choice")?.let { list ->
                val current = Prefs.int(requireContext(), Prefs.KEY_GLOBAL_COLOR_INDEX,
                                        Prefs.DEFAULT_GLOBAL_COLOR_INDEX)
                list.value = current.toString()
                list.summary = summaryForColorIndex(list, current)
                list.setOnPreferenceChangeListener { _, newVal ->
                    val idx = (newVal as? String)?.toIntOrNull() ?: Prefs.DEFAULT_GLOBAL_COLOR_INDEX
                    Prefs.setInt(requireContext(), Prefs.KEY_GLOBAL_COLOR_INDEX, idx)
                    list.summary = summaryForColorIndex(list, idx)
                    if (idx == Prefs.CUSTOM_COLOR_SENTINEL) {
                        showCustomColorPicker { picked ->
                            Prefs.setInt(requireContext(), Prefs.KEY_GLOBAL_COLOR_CUSTOM, picked)
                            list.summary = summaryForColorIndex(list, Prefs.CUSTOM_COLOR_SENTINEL)
                        }
                    }
                    true
                }
            }

            // --- Monochrome icons / Use global color are SwitchPreferenceCompat,
            //     no additional setup needed — preferenceManager saves them automatically 
            //     to the same SharedPreferences ("metro_launcher_prefs") read by Prefs.bool.

            // --- Notification access
            findPreference<Preference>("notification_access")?.setOnPreferenceClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                true
            }

            // --- Refresh notifications
            findPreference<Preference>("notification_refresh")?.setOnPreferenceClickListener {
                NotificationListener.requestRebind()
                android.widget.Toast.makeText(requireContext(), "Requesting notification synchronization...", android.widget.Toast.LENGTH_SHORT).show()
                true
            }

            // --- Reset Start
            findPreference<Preference>("reset_tiles")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext(),
                    R.style.Theme_MetroLauncher_Dialog)
                    .setTitle(R.string.reset_tiles_title)
                    .setMessage(R.string.reset_tiles_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        TileStorage(requireContext()).clear()
                        Prefs.setBool(requireContext(), Prefs.KEY_FIRST_RUN, true)
                        requireActivity().finishAffinity()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        private fun updateWallpaperSummary() {
            val current = Prefs.string(requireContext(), Prefs.KEY_WALLPAPER_URI)
            val p = findPreference<Preference>("pick_wallpaper") ?: return
            p.summary = if (current != null) getString(R.string.wallpaper_custom_set)
                        else getString(R.string.wallpaper_using_system)
        }

        /** Summary for the global color ListPreference.
         *  For indices 0..9 shows Metro name; for -1 shows "Custom: #RRGGBB". */
        private fun summaryForColorIndex(list: ListPreference, idx: Int): CharSequence? {
            if (idx == Prefs.CUSTOM_COLOR_SENTINEL) {
                val c = Prefs.int(requireContext(), Prefs.KEY_GLOBAL_COLOR_CUSTOM,
                                  Prefs.DEFAULT_GLOBAL_COLOR_CUSTOM)
                val hex = String.format("#%02X%02X%02X",
                    android.graphics.Color.red(c),
                    android.graphics.Color.green(c),
                    android.graphics.Color.blue(c))
                return "Custom: $hex"
            }
            // ListPreference exposes `entryValues` parallel to `entries`: we look for 
            // the position of the value matching idx, not idx as a direct index — 
            // this way if we change the order of items tomorrow nothing breaks.
            val values = list.entryValues ?: return null
            val pos = values.indexOfFirst { it?.toString()?.toIntOrNull() == idx }
            return if (pos >= 0) list.entries?.getOrNull(pos)?.toString() else null
        }

        /** Custom color dialog: three SeekBar R/G/B with live preview and hex.
         *  Calls `onConfirm(color)` if user presses OK; nothing if canceled. */
        private fun showCustomColorPicker(onConfirm: (Int) -> Unit) {
            val ctx = requireContext()
            val dm = resources.displayMetrics
            fun dp(px: Float) = (px * dm.density).toInt()

            val initial = Prefs.int(ctx, Prefs.KEY_GLOBAL_COLOR_CUSTOM,
                                    Prefs.DEFAULT_GLOBAL_COLOR_CUSTOM)
            var r = android.graphics.Color.red(initial)
            var g = android.graphics.Color.green(initial)
            var b = android.graphics.Color.blue(initial)

            val root = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20f), dp(16f), dp(20f), dp(8f))
            }
            val preview = android.view.View(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(64f)
                ).apply { bottomMargin = dp(12f) }
                setBackgroundColor(android.graphics.Color.rgb(r, g, b))
            }
            val hex = android.widget.TextView(ctx).apply {
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, dp(12f))
                text = String.format("#%02X%02X%02X", r, g, b)
            }

            fun refresh() {
                val c = android.graphics.Color.rgb(r, g, b)
                preview.setBackgroundColor(c)
                hex.text = String.format("#%02X%02X%02X", r, g, b)
            }

            fun makeRow(label: String, init: Int, accent: Int,
                        onChange: (Int) -> Unit): android.widget.LinearLayout {
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tv = android.widget.TextView(ctx).apply {
                    text = label
                    setTextColor(accent)
                    textSize = 14f
                    minWidth = dp(28f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                val seek = android.widget.SeekBar(ctx).apply {
                    max = 255
                    progress = init
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { leftMargin = dp(12f) }
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                            onChange(v); refresh()
                        }
                        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                    })
                }
                row.addView(tv); row.addView(seek)
                return row
            }

            root.addView(preview)
            root.addView(hex)
            root.addView(makeRow(getString(R.string.custom_color_red).take(1),
                                 r, 0xFFE53935.toInt()) { r = it })
            root.addView(makeRow(getString(R.string.custom_color_green).take(1),
                                 g, 0xFF43A047.toInt()) { g = it })
            root.addView(makeRow(getString(R.string.custom_color_blue).take(1),
                                 b, 0xFF1E88E5.toInt()) { b = it })

            androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_MetroLauncher_Dialog)
                .setTitle(R.string.custom_color_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onConfirm(android.graphics.Color.rgb(r, g, b) or 0xFF000000.toInt())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
