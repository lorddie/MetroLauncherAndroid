package com.metrolauncher.util

/**
 * Riconoscimento delle app "specializzate" in base al package name.
 * Usato dal menu tile per proporre l'opzione "trasforma in live tile calendar/gallery".
 */
object KnownPackages {

    private val CALENDAR_PACKAGES = setOf(
        "com.google.android.calendar",
        "com.android.calendar",
        "com.samsung.android.calendar",
        "com.miui.calendar",
        "ch.protonmail.android.calendar",
        "me.proton.android.calendar",
        "com.microsoft.office.outlook",
        "com.fsck.k9",
        "com.boohee.one"
    )

    private val GALLERY_PACKAGES = setOf(
        "com.google.android.apps.photos",
        "com.sec.android.gallery3d",
        "com.miui.gallery",
        "com.oneplus.gallery",
        "com.simplemobiletools.gallery.pro",
        "com.simplemobiletools.gallery",
        "com.fossify.gallery",
        "app.grapheneos.gallery",
        "com.piyush.photos",
        "app.immich.mobile",
        "com.stfalcon.imageviewer",
        "org.fossify.gallery",
        "com.google.android.apps.nbu.files"
    )

    private val CLOCK_PACKAGES = setOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.samsung.android.app.clockpackage",
        "com.android.alarmclock",
        "com.android.clock",
        "com.oneplus.deskclock",
        "com.coloros.alarmclock",
        "com.oppo.alarmclock",
        "com.asus.deskclock",
        "com.htc.android.worldclock",
        "com.lge.clock",
        "com.motorola.blur.alarmclock",
        "com.sonymobile.alarmclock",
        "com.xiaomi.alarmclock",
        "ch.bailu.aat_gpl",
        "com.simplemobiletools.clock",
        "com.simplemobiletools.clock.pro",
        "org.fossify.clock",
        "com.fossify.clock"
    )

    private val WEATHER_PACKAGES = setOf(
        "com.accuweather.android",
        "com.weather.Weather",          // The Weather Channel
        "com.yahoo.mobile.client.android.weather",
        "com.samsung.android.weather",
        "com.miui.weather2",
        "com.huawei.android.totemweather",
        "com.oneplus.weather",
        "com.coloros.weather",
        "com.oppo.weather",
        "com.asus.weathertime",
        "com.droid27.transparentclockweather",
        "com.tafayor.weatherblack",
        "net.androgames.level.weather",
        "ru.yandex.weatherplugin",
        "com.devexpert.weather",
        "ru.gismeteo.gismeteo",
        "cz.ackee.meteoradar",
        "com.windyty.android",
        "com.mobilesoft.weather",
        "com.sam.zweather",
        "io.rocketblog.weathex",
        "fr.meteoconsult.android",
        "com.ilmeteo.android.ilmeteo",   // IlMeteo (IT)
        "it.iltempo.meteo"
    )

    fun isClock(packageName: String): Boolean =
        packageName in CLOCK_PACKAGES ||
        packageName.contains("deskclock", ignoreCase = true) ||
        packageName.contains("alarmclock", ignoreCase = true) ||
        (packageName.contains("clock", ignoreCase = true) &&
         !packageName.contains("stock", ignoreCase = true))

    fun isWeather(packageName: String): Boolean =
        packageName in WEATHER_PACKAGES ||
        packageName.contains("weather", ignoreCase = true) ||
        packageName.contains("meteo", ignoreCase = true) ||
        packageName.contains("tempo", ignoreCase = true) ||
        packageName.contains("accuweather", ignoreCase = true)

    fun isCalendar(packageName: String): Boolean =
        packageName in CALENDAR_PACKAGES || packageName.contains("calendar", ignoreCase = true)

    fun isGallery(packageName: String): Boolean =
        packageName in GALLERY_PACKAGES ||
        packageName.contains("gallery", ignoreCase = true) ||
        packageName.contains("photos", ignoreCase = true) ||
        packageName.contains("immich", ignoreCase = true)
}
