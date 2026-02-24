package com.example.testkotlinapp

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import androidx.recyclerview.widget.DiffUtil

class ImageUpdater(
    private val context: Context,
    private val recyclerView: RecyclerView
) {

    private val imageFiles = mutableListOf<File>()
    private val adapter = ImagesAdapter(imageFiles) { imageFile ->
        // Handle image click - open viewer activity
        val intent = android.content.Intent(context, ImageViewerActivity::class.java).apply {
            putExtra("imagePath", imageFile.absolutePath)
        }
        context.startActivity(intent)
    }

    init {
        recyclerView.adapter = adapter
    }

    // Load images from internal storage folder and update adapter
    fun loadImages() {
        val newList = listSavedImages(context)
        val oldList = ArrayList(imageFiles)

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].absolutePath ==
                        newList[newItemPosition].absolutePath
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return true // file path identity is enough
            }
        })

        recyclerView.post {
            imageFiles.clear()
            imageFiles.addAll(newList)
            diffResult.dispatchUpdatesTo(adapter)
        }
    }


    // Add a single image file dynamically and update adapter
    fun addImage(file: File) {
        recyclerView.post {
            imageFiles.add(0, file) // optional: add at top
            adapter.notifyItemInserted(0)
        }
    }


    private fun listSavedImages(context: Context): List<File> {
        return context.filesDir.listFiles()?.filter {
            it.extension.equals("jpg", ignoreCase = true) || it.extension.equals("jpeg", ignoreCase = true)
        } ?: emptyList()
    }
}
