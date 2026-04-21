package com.nabla.docscan.ui.review

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nabla.docscan.R
import com.nabla.docscan.databinding.ActivityReviewBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URIS = "extra_image_uris"
        const val EXTRA_ACTION = "action"
        const val ACTION_ADD_PAGES = "add_pages"
        const val ACTION_DONE = "done"
    }

    private lateinit var binding: ActivityReviewBinding
    private lateinit var adapter: PageThumbnailAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_review)

        adapter = PageThumbnailAdapter(onDelete = { position ->
            adapter.removePage(position)
            updatePageCount()
        })
        binding.rvPages.layoutManager = GridLayoutManager(this, 2)
        binding.rvPages.adapter = adapter

        val uriStrings = intent.getStringArrayListExtra(EXTRA_IMAGE_URIS) ?: emptyList<String>()
        adapter.submitList(uriStrings.map { Uri.parse(it) })
        updatePageCount()

        // "Add Pages" — return to ScanActivity and trigger scanner
        binding.btnRescan.text = getString(R.string.btn_add_pages)
        binding.btnRescan.setOnClickListener {
            finishWithResult(ACTION_ADD_PAGES)
        }

        // "Done" — return updated URI list
        binding.btnProcessAndUpload.setOnClickListener {
            finishWithResult(ACTION_DONE)
        }
    }

    private fun updatePageCount() {
        val count = adapter.itemCount
        binding.tvPageCount.text = getString(R.string.label_page_count, count)
        binding.btnProcessAndUpload.isEnabled = count > 0
        if (count == 0) {
            binding.tvPageCount.text = "No pages. Tap Add Pages to scan."
        }
    }

    private fun finishWithResult(action: String) {
        val remaining = ArrayList(adapter.getUris().map { it.toString() })
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_ACTION, action)
            .putStringArrayListExtra(EXTRA_IMAGE_URIS, remaining))
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finishWithResult(ACTION_DONE)
        return true
    }

    override fun onBackPressed() {
        finishWithResult(ACTION_DONE)
    }
}

class PageThumbnailAdapter(
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PageThumbnailAdapter.PageViewHolder>() {

    private val pages = mutableListOf<Uri>()

    fun submitList(newPages: List<Uri>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    fun removePage(position: Int) {
        if (position in pages.indices) {
            pages.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, pages.size)
        }
    }

    fun getUris(): List<Uri> = pages.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_page_thumbnail, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position], position, onDelete)
    }

    override fun getItemCount() = pages.size

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        private val tvPageNumber: TextView = itemView.findViewById(R.id.tv_page_number)
        private val btnDelete: ImageButton? = itemView.findViewById(R.id.btn_delete_page)

        fun bind(page: Uri, position: Int, onDelete: (Int) -> Unit) {
            tvPageNumber.text = itemView.context.getString(R.string.label_page_number, position + 1)
            ivThumbnail.load(page) {
                crossfade(true)
                placeholder(R.drawable.ic_image_placeholder)
            }
            btnDelete?.setOnClickListener { onDelete(adapterPosition) }
        }
    }
}
