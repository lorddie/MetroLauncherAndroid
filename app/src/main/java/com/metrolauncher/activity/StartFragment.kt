package com.metrolauncher.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.metrolauncher.R
import com.metrolauncher.view.TileGridLayout

/**
 * Fragment that hosts the Start screen (tile grid). Thin wrapper: exposes 
 * views to MainActivity which does all the real work.
 */
class StartFragment : Fragment() {

    lateinit var tileGrid: TileGridLayout
        private set
    lateinit var scrollView: androidx.core.widget.NestedScrollView
        private set
    lateinit var hintArrow: TextView
        private set
    lateinit var tileMask: com.metrolauncher.view.TileMaskView
        private set
    lateinit var searchBar: com.metrolauncher.view.SearchBarView
        private set
    lateinit var pullDownLayout: com.metrolauncher.view.SearchPullDownLayout
        private set

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_start, container, false)
        tileGrid = v.findViewById(R.id.tile_grid)
        scrollView = v.findViewById(R.id.tile_scroll)
        hintArrow = v.findViewById(R.id.hint_arrow)
        tileMask = v.findViewById(R.id.tile_mask)
        searchBar = v.findViewById(R.id.search_bar)
        pullDownLayout = v as com.metrolauncher.view.SearchPullDownLayout
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.onStartFragmentReady(this)
    }
}
