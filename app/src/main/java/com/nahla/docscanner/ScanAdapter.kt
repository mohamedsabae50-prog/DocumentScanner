package com.nahla.docscanner

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nahla.docscanner.databinding.ItemPageBinding

/** Horizontal strip of captured pages, each tappable to re-crop. */
class ScanAdapter(
    private val onOpen: (ScanPage) -> Unit,
    private val onDelete: (ScanPage) -> Unit
) : RecyclerView.Adapter<ScanAdapter.PageHolder>() {

    private val items = mutableListOf<ScanPage>()

    fun add(page: ScanPage) {
        items.add(page)
        notifyItemInserted(items.size - 1)
    }

    fun replace(page: ScanPage) {
        val index = items.indexOfFirst { it.id == page.id }
        if (index >= 0) {
            items[index] = page
            notifyItemChanged(index)
        }
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun clear() {
        val count = items.size
        if (count == 0) return
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val page = items[position]
        holder.binding.thumb.setImageBitmap(page.thumb)
        holder.binding.pageNumber.text = (position + 1).toString()
        holder.binding.thumb.setOnClickListener { onOpen(page) }
        holder.binding.delete.setOnClickListener { onDelete(page) }
    }

    override fun getItemCount(): Int = items.size

    class PageHolder(val binding: ItemPageBinding) : RecyclerView.ViewHolder(binding.root)
}