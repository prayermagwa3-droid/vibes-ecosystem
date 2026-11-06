package com.vibes.only.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vibes.only.MainActivity
import com.vibes.only.R
import com.vibes.only.VoiceStudioActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact home-screen launcher widget. It does not expose conversation content. */
class VyronHomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_vyron_home)
            views.setTextViewText(R.id.widgetUpdatedAt, "Ready • ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}")
            views.setOnClickPendingIntent(R.id.widgetAskButton, launch(context, MainActivity::class.java, 1000 + id))
            views.setOnClickPendingIntent(R.id.widgetVoiceButton, launch(context, VoiceStudioActivity::class.java, 2000 + id))
            views.setOnClickPendingIntent(R.id.widgetRoot, launch(context, MainActivity::class.java, 3000 + id))
            manager.updateAppWidget(id, views)
        }
    }

    private fun launch(context: Context, target: Class<*>, requestCode: Int): PendingIntent {
        val intent = Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
