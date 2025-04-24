package com.example.tymr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tymr.service.UpdateWorker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SourceAdapter
    private val sourceUrls = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recycler_sources)
        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.addItemDecoration(DividerItemDecoration(this, layoutManager.orientation))

        // Load saved sources
        loadSources()

        adapter = SourceAdapter(sourceUrls) { url, isActive ->
            saveSourceState(url, isActive)
        }
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add_source).setOnClickListener {
            showAddSourceDialog()
        }

        // Show notification settings
        findViewById<Button>(R.id.btn_notification_settings).setOnClickListener {
            showNotificationSettingsDialog()
        }

        // Initialize the service
        UpdateWorker.schedulePeriodicWork(this)
    }

    private fun loadSources() {
        val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
        val savedUrls = prefs.getStringSet("source_urls", emptySet()) ?: emptySet()
        sourceUrls.clear()
        sourceUrls.addAll(savedUrls)
    }

    private fun saveSource(url: String) {
        val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
        val savedUrls = prefs.getStringSet("source_urls", emptySet())?.toMutableSet() ?: mutableSetOf()
        savedUrls.add(url)

        prefs.edit() {
            putStringSet("source_urls", savedUrls)
                .putBoolean("active_$url", true)  // New sources are active by default
        }

        sourceUrls.add(url)
        adapter.notifyItemInserted(sourceUrls.size - 1)

        // Trigger an update
        UpdateWorker.schedulePeriodicWork(this)
    }

    private fun removeSource(url: String) {
        val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
        val savedUrls = prefs.getStringSet("source_urls", emptySet())?.toMutableSet() ?: mutableSetOf()
        savedUrls.remove(url)

        prefs.edit() {
            putStringSet("source_urls", savedUrls)
                .remove("active_$url")
        }

        val index = sourceUrls.indexOf(url)
        if (index != -1) {
            sourceUrls.removeAt(index)
            adapter.notifyItemRemoved(index)
        }

        // Trigger an update
        UpdateWorker.schedulePeriodicWork(this)
    }

    private fun saveSourceState(url: String, isActive: Boolean) {
        val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
        prefs.edit() {
            putBoolean("active_$url", isActive)
        }

        // Trigger an update
        UpdateWorker.schedulePeriodicWork(this)
    }

    private fun showAddSourceDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.enter_ics_url)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_calendar_source))
            .setView(input)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    saveSource(url)
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun showEditSourceDialog(url: String, position: Int) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(url)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_calendar_source))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newUrl = input.text.toString().trim()
                if (newUrl.isNotEmpty() && newUrl != url) {
                    removeSource(url)
                    saveSource(newUrl)
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .setNeutralButton(getString(R.string.delete)) { _, _ ->
                removeSource(url)
            }
            .show()
    }

    private fun showNotificationSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_notification_settings, null)

        val switchEnableNotification = view.findViewById<SwitchMaterial>(R.id.switch_enable_notification)
        val switchShowDetails = view.findViewById<SwitchMaterial>(R.id.switch_show_details)
        val switchShowLocation = view.findViewById<SwitchMaterial>(R.id.switch_show_location)

        val prefs = getSharedPreferences("EventPrefs", Context.MODE_PRIVATE)
        switchEnableNotification.isChecked = prefs.getBoolean("enable_notification", true)
        switchShowDetails.isChecked = prefs.getBoolean("show_details", true)
        switchShowLocation.isChecked = prefs.getBoolean("show_location", true)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notification_settings))
            .setView(view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                prefs.edit() {
                    putBoolean("enable_notification", switchEnableNotification.isChecked)
                    putBoolean("show_details", switchShowDetails.isChecked)
                    putBoolean("show_location", switchShowLocation.isChecked)
                }

                // Update the service based on notification setting
                val serviceIntent = Intent(this, com.example.tymr.service.ForegroundService::class.java)
                if (switchEnableNotification.isChecked) {
                    UpdateWorker.schedulePeriodicWork(this)
                } else {
                    stopService(serviceIntent)
                    UpdateWorker.cancelPeriodicWork(this)
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    inner class SourceAdapter(
        private val items: List<String>,
        private val onSwitchChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textUrl: TextView = view.findViewById(R.id.text_source_url)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_source_active)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_calendar_source, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val url = items[position]
            holder.textUrl.text = url

            val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
            holder.switchActive.isChecked = prefs.getBoolean("active_$url", true)

            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                onSwitchChanged(url, isChecked)
            }

            holder.itemView.setOnClickListener {
                showEditSourceDialog(url, position)
            }
        }

        override fun getItemCount() = items.size
    }
}