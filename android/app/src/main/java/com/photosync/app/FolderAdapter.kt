package com.photosync.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * Shows each device folder with a cover thumbnail, name, and item count, plus
 * a checkbox for including it in the backup. Checkboxes act only while
 * [enabled] (i.e. when "Selected folders" mode is active).
 */
class FolderAdapter(
    private val selected: MutableSet<String>,
    private val onToggle: (bucketId: String, checked: Boolean) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.Holder>() {

    private var folders: List<MediaFolder> = emptyList()
    private var enabled: Boolean = false

    fun submit(list: List<MediaFolder>) {
        folders = list
        notifyDataSetChanged()
    }

    fun setEnabled(value: Boolean) {
        if (enabled != value) {
            enabled = value
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = folders.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(folders[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.folderCover)
        private val name: TextView = view.findViewById(R.id.folderName)
        private val count: TextView = view.findViewById(R.id.folderCount)
        private val check: CheckBox = view.findViewById(R.id.folderCheck)

        fun bind(folder: MediaFolder) {
            Glide.with(cover).load(folder.coverUri).centerCrop().into(cover)
            name.text = if (folder.isCameraRoll) {
                itemView.context.getString(R.string.folder_camera_label, folder.name)
            } else {
                folder.name
            }
            count.text = itemView.resources.getQuantityString(
                R.plurals.folder_item_count, folder.count, folder.count
            )

            check.setOnCheckedChangeListener(null)
            check.isChecked = folder.bucketId in selected
            check.isEnabled = enabled
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected += folder.bucketId else selected -= folder.bucketId
                onToggle(folder.bucketId, isChecked)
            }

            itemView.alpha = if (enabled) 1f else 0.5f
            itemView.isEnabled = enabled
            itemView.setOnClickListener { if (enabled) check.isChecked = !check.isChecked }
        }
    }
}
