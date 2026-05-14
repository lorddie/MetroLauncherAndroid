package com.metrolauncher.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metrolauncher.MetroApp
import com.metrolauncher.R
import com.metrolauncher.adapter.AppListAdapter
import com.metrolauncher.model.AppInfo
import com.metrolauncher.util.AppLoader
import com.metrolauncher.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Drawer "Tutte le app". Lista verticale con intestazioni per iniziale (A, B, C...)
 * come nel drawer di Windows Phone 10.
 *
 * Tap singolo       -> lancia l'app
 * Tap lungo su app  -> dialog con "Aggiungi alla Start" / "Info app"
 * Swipe sx->dx      -> torna alla Start (chiude l'activity)
 * Barra di ricerca in alto per filtrare rapidamente.
 */
class AllAppsActivity : AppCompatActivity() {

    private val scope = MainScope()
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: AppListAdapter
    private lateinit var searchInput: EditText
    private lateinit var emptyView: TextView
    private var fullList: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_all_apps)
        window.statusBarColor = 0
        window.navigationBarColor = 0

        recycler = findViewById(R.id.app_recycler)
        searchInput = findViewById(R.id.search_input)
        emptyView = findViewById(R.id.empty_view)

        adapter = AppListAdapter(
            onClick = { app -> launchApp(app) },
            onLongClick = { app -> showAppMenu(app) },
            onHeaderClick = {},
            fontScale = Prefs.fontScaleFactor(
                Prefs.string(this, Prefs.KEY_DRAWER_FONT_SCALE, Prefs.DEFAULT_FONT_SCALE)
            )
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        setupGesture()
        setupSearch()

        loadApps()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    override fun onResume() {
        super.onResume()
        // Applica l'eventuale nuovo font scale senza ricostruire l'activity
        adapter.setFontScale(Prefs.fontScaleFactor(
            Prefs.string(this, Prefs.KEY_DRAWER_FONT_SCALE, Prefs.DEFAULT_FONT_SCALE)
        ))
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun loadApps() {
        val cached = (application as MetroApp).appsCache
        if (cached != null) {
            applyList(cached)
        }
        scope.launch {
            val apps = withContext(Dispatchers.IO) { AppLoader.loadAll(this@AllAppsActivity) }
            (application as MetroApp).appsCache = apps
            applyList(apps)
        }
    }

    private fun applyList(apps: List<AppInfo>) {
        fullList = apps
        adapter.submit(AppLoader.groupByInitial(apps))
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                if (q.isEmpty()) {
                    adapter.submit(AppLoader.groupByInitial(fullList))
                } else {
                    val filtered = fullList.filter { it.label.contains(q, ignoreCase = true) }
                    adapter.submit(AppLoader.groupByInitial(filtered))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupGesture() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                e1 ?: return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // Swipe sinistra-verso-destra = torna alla Start
                if (abs(dx) > abs(dy) && dx > 200 && abs(velocityX) > 800) {
                    finish()
                    return true
                }
                return false
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev); false
        }
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
        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        // Chiediamo alla MainActivity di pinnare
                        val i = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(EXTRA_PIN_PACKAGE, app.packageName)
                            putExtra(EXTRA_PIN_ACTIVITY, app.activityName)
                            putExtra(EXTRA_PIN_LABEL, app.label)
                        }
                        startActivity(i)
                        finish()
                    }
                    1 -> {
                        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", app.packageName, null))
                        startActivity(i)
                    }
                    2 -> {
                        val i = Intent(Intent.ACTION_DELETE)
                            .setData(android.net.Uri.fromParts("package", app.packageName, null))
                        startActivity(i)
                    }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_PIN_PACKAGE = "pin_pkg"
        const val EXTRA_PIN_ACTIVITY = "pin_act"
        const val EXTRA_PIN_LABEL = "pin_label"
    }
}
