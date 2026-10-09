package com.chatlens.fakewa

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Verlauf mit scrollbarer Nachrichtenliste (RecyclerView), Datumstrennern, Sprachnachrichten (SeekBar), Eingabefeld. Startet unten. */
class ConversationActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val title = intent.getStringExtra("title") ?: "Chat"
        val msgs = Data.messages(title)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(235, 230, 220)) }
        col.addView(TextView(this).apply {
            id = R.id.conversation_title; text = title; textSize = 20f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0, 100, 80)); setPadding(dp(16), dp(36), dp(16), dp(12))
        })
        val list = RecyclerView(this).apply {
            id = android.R.id.list
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply { stackFromEnd = true }
            adapter = MsgAdapter(msgs)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        col.addView(list)
        val inputBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        inputBar.addView(EditText(this).apply { id = R.id.entry; hint = "Nachricht"; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        inputBar.addView(ImageButton(this).apply { id = R.id.send; contentDescription = "Senden"; setImageResource(android.R.drawable.ic_menu_send); setBackgroundColor(Color.TRANSPARENT) })
        col.addView(inputBar)
        setContentView(col)
        list.scrollToPosition(msgs.size - 1)
    }

    inner class MsgAdapter(val m: List<Data.Msg>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = m.size
        override fun getItemViewType(p: Int) = m[p].kind * 2 + if (m[p].out) 1 else 0
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val kind = t / 2
            val out = t % 2 == 1
            val row = LinearLayout(this@ConversationActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = if (kind == 1 || kind == 3) Gravity.CENTER_HORIZONTAL else if (out) Gravity.END else Gravity.START
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }
            val bubble = LinearLayout(this@ConversationActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(if (out) Color.rgb(220, 248, 198) else Color.WHITE); setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            row.addView(bubble)
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val msg = m[pos]
            val bubble = (h.itemView as LinearLayout).getChildAt(0) as LinearLayout
            bubble.removeAllViews()
            when (msg.kind) {
                1 -> bubble.addView(TextView(this@ConversationActivity).apply { id = R.id.date_separator; text = msg.text; textSize = 13f })
                3 -> bubble.addView(TextView(this@ConversationActivity).apply { text = msg.text; textSize = 12f })
                2 -> {
                    bubble.addView(LinearLayout(this@ConversationActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(SeekBar(this@ConversationActivity).apply { id = R.id.voice_seekbar; layoutParams = LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT) })
                        addView(TextView(this@ConversationActivity).apply { text = msg.text; textSize = 13f })
                    })
                    bubble.addView(TextView(this@ConversationActivity).apply { text = msg.time; textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.END })
                }
                else -> {
                    bubble.addView(TextView(this@ConversationActivity).apply { id = R.id.message_text; text = msg.text; textSize = 16f; setTextColor(Color.BLACK); maxWidth = dp(280) })
                    bubble.addView(TextView(this@ConversationActivity).apply { text = msg.time; textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.END })
                }
            }
        }
    }
}

class ArchiveActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val list = RecyclerView(this).apply {
            id = android.R.id.list
            layoutManager = LinearLayoutManager(this@ArchiveActivity)
            adapter = ChatAdapter(this@ArchiveActivity, Data.archived(), false) { c ->
                if (c != null) startActivity(Intent(this@ArchiveActivity, ConversationActivity::class.java).putExtra("title", c.title))
            }
        }
        setContentView(list)
    }
}

/** Loest per Broadcast eine Heads-up-Benachrichtigung (hohe Wichtigkeit, Ton) aus, um Eingriffe von oben zu provozieren. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fake_high", "Fake hoch", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 0, Intent(c, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, "fake_high").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Anna Schmidt").setContentText("Hast du Zeit?").setContentIntent(pi).setAutoCancel(true).build()
        nm.notify((System.nanoTime() % 100000).toInt(), n)
    }
}
