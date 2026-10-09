package com.chatlens.fakewa

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Eine Chatzeile mit den IDs, die ChatLens aus dem Profil kennt (conversations_row_contact_name, contact_row_container, ...). */
class RowHolder(v: View, val name: TextView, val preview: TextView, val time: TextView, val badge: TextView) : RecyclerView.ViewHolder(v)

fun Activity.rowView(): RowHolder {
    val c = LinearLayout(this).apply {
        id = R.id.contact_row_container
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76))
    }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
    val name = TextView(this).apply { id = R.id.conversations_row_contact_name; textSize = 17f; setTextColor(Color.BLACK); maxLines = 1 }
    val prev = TextView(this).apply { id = R.id.conversations_row_message_preview; textSize = 14f; setTextColor(Color.DKGRAY); maxLines = 1 }
    col.addView(name); col.addView(prev)
    val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    val time = TextView(this).apply { id = R.id.conversations_row_date; textSize = 12f; setTextColor(Color.GRAY) }
    val badge = TextView(this).apply { id = R.id.conversations_row_message_count; textSize = 12f; setTextColor(Color.rgb(0, 150, 100)) }
    right.addView(time); right.addView(badge)
    c.addView(col); c.addView(right)
    return RowHolder(c, name, prev, time, badge)
}

class ChatAdapter(val act: Activity, var items: List<FakeChat>, val archiveRow: Boolean, val onClick: (FakeChat?) -> Unit) : RecyclerView.Adapter<RowHolder>() {
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = act.rowView()
    override fun getItemCount() = items.size + if (archiveRow) 1 else 0
    override fun onBindViewHolder(h: RowHolder, pos: Int) {
        if (archiveRow && pos == 0) {
            h.name.text = "Archiviert"; h.preview.text = ""; h.time.text = ""; h.badge.text = ""
            h.itemView.setOnClickListener { onClick(null) }
            return
        }
        val c = items[pos - if (archiveRow) 1 else 0]
        h.name.text = c.title; h.preview.text = c.preview; h.time.text = c.time
        h.badge.text = if (c.unread > 0) c.unread.toString() else ""
        h.itemView.setOnClickListener { onClick(c) }
    }
}

/** Hauptseite: Kopfzeile mit Lupe, ViewPager2 mit vier Seiten (Chats mit RecyclerView android:id/list), untere Tableiste, Suchfeld als Ueberlagerung. */
class HomeActivity : Activity() {
    private lateinit var search: LinearLayout
    private lateinit var field: EditText
    private lateinit var results: RecyclerView
    private lateinit var resultAdapter: ChatAdapter
    private lateinit var chats: List<FakeChat>

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val n = intent.getIntExtra("chats", 200)
        chats = Data.chats(n)
        val root = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        // Kopfzeile
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(0, 100, 80)); setPadding(dp(16), dp(36), dp(8), dp(8)) }
        bar.addView(TextView(this).apply { text = "WhatsApp"; textSize = 22f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        bar.addView(ImageButton(this).apply {
            id = R.id.menuitem_search; contentDescription = "Suchen"; setImageResource(android.R.drawable.ic_menu_search); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showSearch(true) }
        })
        col.addView(bar)
        // Seiten
        val pager = ViewPager2(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        pager.adapter = Pages()
        col.addView(pager)
        // Tableiste unten
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.rgb(245, 245, 245)) }
        listOf("Chats", "Aktuelles", "Communitys", "Anrufe").forEachIndexed { i, t ->
            tabs.addView(TextView(this).apply {
                text = t; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, dp(16), 0, dp(24)); isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { pager.currentItem = i }
            })
        }
        col.addView(tabs)
        root.addView(col)
        // Suche
        search = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); visibility = View.GONE; setPadding(0, dp(36), 0, 0) }
        field = EditText(this).apply {
            id = R.id.search_src_text; hint = "Suchen"; setSingleLine(); setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { refreshResults(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        search.addView(field)
        search.addView(TextView(this).apply { id = R.id.search_header; text = "Chats"; textSize = 13f; setTextColor(Color.rgb(0, 120, 90)); setPadding(dp(16), dp(8), 0, dp(4)) })
        results = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@HomeActivity) }
        resultAdapter = ChatAdapter(this, emptyList(), false) { c -> if (c != null) openChat(c.title) }
        results.adapter = resultAdapter
        search.addView(results)
        root.addView(search)
        setContentView(root)
    }

    private fun showSearch(on: Boolean) {
        search.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { field.setText(""); field.requestFocus(); refreshResults("") }
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    private fun refreshResults(q: String) {
        val qq = norm(q)
        resultAdapter.items = if (qq.isBlank()) emptyList() else chats.filter { norm(it.title).contains(qq) }.take(30)
        resultAdapter.notifyDataSetChanged()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (search.visibility == View.VISIBLE) showSearch(false) else super.onBackPressed()
    }

    private fun openChat(title: String) {
        startActivity(Intent(this, ConversationActivity::class.java).putExtra("title", title))
    }

    inner class Pages : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 4
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val v: View = if (t == 0) RecyclerView(this@HomeActivity).apply {
                id = android.R.id.list
                layoutManager = LinearLayoutManager(this@HomeActivity)
                adapter = ChatAdapter(this@HomeActivity, chats, true) { c ->
                    if (c == null) startActivity(Intent(this@HomeActivity, ArchiveActivity::class.java)) else openChat(c.title)
                }
            } else TextView(this@HomeActivity).apply { text = listOf("", "Aktuelles", "Communitys", "Anrufe")[t]; gravity = Gravity.CENTER }
            v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {}
        override fun getItemViewType(position: Int) = position
    }
}
