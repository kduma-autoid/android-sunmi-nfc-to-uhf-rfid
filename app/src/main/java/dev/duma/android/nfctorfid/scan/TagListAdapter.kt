package dev.duma.android.nfctorfid.scan

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.duma.android.nfctorfid.R
import dev.duma.android.nfctorfid.databinding.ItemTagBinding
import dev.duma.android.nfctorfid.epc.colonizeHex

class TagListAdapter : RecyclerView.Adapter<TagListAdapter.Holder>() {

    private val items = mutableListOf<ScanFragment.ScannedTag>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(tags: List<ScanFragment.ScannedTag>) {
        items.clear()
        items.addAll(tags)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    class Holder(private val binding: ItemTagBinding) : RecyclerView.ViewHolder(binding.root) {

        private val defaultColors: ColorStateList = binding.tvItemUid.textColors

        fun bind(tag: ScanFragment.ScannedTag) {
            val context = binding.root.context
            binding.tvItemUid.text = tag.uidHex.colonizeHex()
            when {
                tag.seenNfc && !tag.seenUhf ->
                    binding.tvItemUid.setTextColor(ContextCompat.getColor(context, R.color.error))
                tag.seenNfc && tag.seenUhf ->
                    binding.tvItemUid.setTextColor(ContextCompat.getColor(context, R.color.success))
                else -> binding.tvItemUid.setTextColor(defaultColors)
            }
            binding.tvItemDetails.text = if (tag.seenUhf) {
                context.getString(
                    R.string.scan_item_details,
                    tag.rssiDbm?.let { "$it dBm" } ?: "?",
                    tag.readCount,
                )
            } else {
                context.getString(R.string.scan_item_missing)
            }
        }
    }
}
