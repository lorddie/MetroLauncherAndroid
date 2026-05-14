package com.metrolauncher.activity

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter con due pagine fisse: Start e Drawer.
 */
class LauncherPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2
    override fun createFragment(position: Int): Fragment = when (position) {
        PAGE_START -> StartFragment()
        PAGE_DRAWER -> DrawerFragment()
        else -> StartFragment()
    }

    companion object {
        const val PAGE_START = 0
        const val PAGE_DRAWER = 1
    }
}
