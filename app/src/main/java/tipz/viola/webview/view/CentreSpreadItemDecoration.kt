package tipz.viola.webview.view

import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration

class CentreSpreadItemDecoration(private val itemWidth: Float, private val itemCount: Int) : ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect, view: View,
        parent: RecyclerView, state: RecyclerView.State
    ) {
        if (parent.layoutManager !is FixedLinearLayoutManager) return

        val displayMetrics: DisplayMetrics = parent.resources.displayMetrics
        val totalWidth = displayMetrics.widthPixels

        val totalWidthFromItems = itemWidth * itemCount
        if (totalWidthFromItems >= totalWidth) return

        val totalRequiredSpace = totalWidth - totalWidthFromItems
        if (totalRequiredSpace < itemCount) return

        val requiredSpace = totalRequiredSpace / (itemCount * 2) - 1
        outRect.left = if (parent.getChildLayoutPosition(view) == 0) (requiredSpace / 2).toInt() else requiredSpace.toInt()
        outRect.right = if (parent.getChildLayoutPosition(view) == itemCount) (requiredSpace / 2).toInt() else requiredSpace.toInt()

        (parent.layoutManager as FixedLinearLayoutManager).setScrollEnabled(false)
    }
}