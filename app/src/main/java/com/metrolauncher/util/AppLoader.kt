package com.metrolauncher.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.metrolauncher.model.AppInfo
import java.text.Collator
import java.util.Locale

/**
 * Loads the list of all launchable apps on the device.
 * Behavior identical to the Windows Phone drawer: case-insensitive alphabetical sorting, 
 * excludes the launcher itself.
 */
object AppLoader {

    fun loadAll(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val flags = PackageManager.MATCH_ALL
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, flags)

        val myPkg = ctx.packageName
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }

        return resolved
            .asSequence()
            .filter { it.activityInfo.packageName != myPkg }
            .map { ri ->
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                    icon = ri.loadIcon(pm)
                )
            }
            .sortedWith(compareBy(collator) { it.label })
            .toList()
    }

    /**
     * Groups apps by initial letter — used by the "all apps" drawer
     * to show section headers in WP style (A, B, C, ...).
     */
    fun groupByInitial(apps: List<AppInfo>): LinkedHashMap<String, List<AppInfo>> {
        val map = linkedMapOf<String, MutableList<AppInfo>>()
        for (app in apps) {
            val key = app.label.firstOrNull()
                ?.takeIf { it.isLetter() }
                ?.uppercase()
                ?: "#"
            map.getOrPut(key) { mutableListOf() }.add(app)
        }
        return LinkedHashMap(map.mapValues { it.value.toList() })
    }

    fun loadIcon(ctx: Context, packageName: String, activityName: String) = runCatching {
        val cn = android.content.ComponentName(packageName, activityName)
        ctx.packageManager.getActivityIcon(cn)
    }.getOrNull()
}
