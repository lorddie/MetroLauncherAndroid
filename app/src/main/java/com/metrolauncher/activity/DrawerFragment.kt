package com.metrolauncher.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metrolauncher.MetroApp
import com.metrolauncher.R
import com.metrolauncher.adapter.AppListAdapter
import com.metrolauncher.model.AppInfo
import com.metrolauncher.util.AppLoader
import com.metrolauncher.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment che ospita la pagina "Tutte le app" nel pager. Self-contained:
 * gestisce la propria lista app, la ricerca e il click-to-launch. Per pin
 * to start delega al MainActivity.
 */
class DrawerFragment : Fragment() {

    private val scope = MainScope()
    private var loadJob: Job? = null
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var searchInput: EditText
    private lateinit var emptyView: TextView
    private lateinit var jumpListOverlay: View
    private lateinit var alphabetGrid: android.widget.GridView
    private var fullList: List<AppInfo> = emptyList()
    private var activeLetters: Set<String> = emptySet()
    private var accentColor: Int = 0xFF0078D7.toInt()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.app_recycler)
        searchInput = view.findViewById(R.id.search_input)
        emptyView = view.findViewById(R.id.empty_view)
        jumpListOverlay = view.findViewById(R.id.jump_list_overlay)
        alphabetGrid = view.findViewById(R.id.alphabet_grid)

        // Gestione status bar insets per non coprire la barra di ricerca
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.drawer_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        adapter = AppListAdapter(
            onClick = { app -> launchApp(app) },
            onLongClick = { app -> showAppMenu(app) },
            onHeaderClick = { showJumpList() },
            fontScale = Prefs.fontScaleFactor(
                Prefs.string(requireContext(), Prefs.KEY_DRAWER_FONT_SCALE, Prefs.DEFAULT_FONT_SCALE)
            )
        )
        recycler.layoutManager = LinearLayoutManager(context)
        recycler.adapter = adapter
        
        setupJumpList()
        setupSearch()
        loadApps()
    }

    private fun setupJumpList() {
        jumpListOverlay.setOnClickListener { jumpListOverlay.visibility = View.GONE }
    }

    private fun showJumpList() {
        val alphabet = ('A'..'Z').map { it.toString() } + "#"
        
        alphabetGrid.adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = alphabet.size
            override fun getItem(position: Int): String = alphabet[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: LayoutInflater.from(context).inflate(R.layout.item_alphabet, parent, false) as TextView
                val letter = alphabet[position]
                tv.text = letter
                
                // Applica il colore globale
                tv.setBackgroundColor(accentColor)
                
                // Se la lettera è presente nel drawer, evidenziala (WP stile)
                val exists = activeLetters.contains(letter)
                tv.alpha = if (exists) 1.0f else 0.25f
                tv.isEnabled = exists
                
                return tv
            }
        }

        alphabetGrid.setOnItemClickListener { _, _, position, _ ->
            val letter = alphabet[position]
            val pos = adapter.getPositionForLetter(letter)
            if (pos >= 0) {
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
            }
            jumpListOverlay.visibility = View.GONE
        }

        jumpListOverlay.visibility = View.VISIBLE
    }

    fun isJumpListOpen(): Boolean = ::jumpListOverlay.isInitialized && jumpListOverlay.visibility == View.VISIBLE

    fun closeJumpList() {
        if (isJumpListOpen()) jumpListOverlay.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        adapter.setFontScale(Prefs.fontScaleFactor(
            Prefs.string(context, Prefs.KEY_DRAWER_FONT_SCALE, Prefs.DEFAULT_FONT_SCALE)
        ))
        
        // Carica il colore globale corrente
        val useGlobal = Prefs.bool(context, Prefs.KEY_USE_GLOBAL_COLOR, false)
        accentColor = if (useGlobal) {
            val idx = Prefs.int(context, Prefs.KEY_GLOBAL_COLOR_INDEX, 0)
            if (idx == Prefs.CUSTOM_COLOR_SENTINEL) Prefs.int(context, Prefs.KEY_GLOBAL_COLOR_CUSTOM, 0xFF0078D7.toInt())
            else com.metrolauncher.util.ColorUtils.METRO_COLORS[idx.coerceIn(0, com.metrolauncher.util.ColorUtils.METRO_COLORS.size - 1)]
        } else {
            0xFF0078D7.toInt() // Default blue
        }
        
        // Applica il colore anche agli header della lista
        adapter.setAccentColor(accentColor)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadApps() {
        val app = requireActivity().application as MetroApp
        val cached = app.appsCache
        if (cached != null) applyList(cached)
        loadJob?.cancel()
        loadJob = scope.launch {
            val apps = withContext(Dispatchers.IO) { AppLoader.loadAll(requireContext()) }
            app.appsCache = apps
            applyList(apps)
        }
    }

    private fun applyList(apps: List<AppInfo>) {
        fullList = apps
        val groups = AppLoader.groupByInitial(apps)
        activeLetters = groups.keys.toSet()
        adapter.submit(groups)
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) adapter.submit(AppLoader.groupByInitial(fullList))
                else {
                    val filtered = fullList.filter { it.label.contains(q, ignoreCase = true) }
                    adapter.submit(AppLoader.groupByInitial(filtered))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun launchApp(app: AppInfo) {
        runCatching {
            val i = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(i)
        }
    }

    private fun showAppMenu(app: AppInfo) {
        val items = arrayOf(
            getString(R.string.pin_to_start),
            getString(R.string.app_info),
            getString(R.string.uninstall)
        )
        AlertDialog.Builder(requireContext(), R.style.Theme_MetroLauncher_Dialog)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        (activity as? MainActivity)?.pinApp(app)
                        // Torna alla Start così l'utente vede il risultato
                        (activity as? MainActivity)?.goToStartPage()
                    }
                    1 -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", app.packageName, null)))
                    2 -> startActivity(Intent(Intent.ACTION_DELETE)
                        .setData(android.net.Uri.fromParts("package", app.packageName, null)))
                }
            }
            .show()
    }
}
