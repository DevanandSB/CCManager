package com.devanandsb.ccmanager.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.devanandsb.ccmanager.MainActivity
import com.devanandsb.ccmanager.R
import com.devanandsb.ccmanager.data.Card
import com.devanandsb.ccmanager.data.Statement
import com.devanandsb.ccmanager.domain.CardWithDue
import com.devanandsb.ccmanager.domain.Dates
import com.devanandsb.ccmanager.domain.Money
import com.devanandsb.ccmanager.ui.CardArts
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Notifications {
    const val CHANNEL = "due_reminders"
    const val CHANNEL_STATEMENTS = "statements"
    const val EXTRA_OPEN_CARD = "open_card"
    const val EXTRA_OPEN_STATEMENT = "open_statement"
    const val EXTRA_OPEN_UPLOAD = "open_upload"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Bill reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders before a credit card bill is due" }
        val st = NotificationChannel(CHANNEL_STATEMENTS, "New statements", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "When a new credit card statement arrives by email" }
        try {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannels(listOf(ch, st))
        } catch (_: Throwable) {}
    }

    /** Single unified notification ID per card so one card never stacks duplicate alerts. */
    fun notificationId(cardId: Long) = (cardId % Int.MAX_VALUE).toInt() + 1000

    /** Distinct notification ID for missing-statement upload prompts so it does not overwrite due alerts. */
    fun missingStatementNotificationId(cardId: Long) = (cardId % Int.MAX_VALUE).toInt() + 500_000

    fun enabled(context: Context): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: Throwable) {
        false
    }

    fun showDue(context: Context, cw: CardWithDue, minDuePaise: Long? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val c = cw.card
        val due = cw.due
        val dueEpoch = due.dueDate.toEpochDay()
        val total = if (c.outstandingPaise > 0) Money.inr(c.outstandingPaise, true) else "₹0"
        val min = minDuePaise?.takeIf { it > 0L }?.let { Money.inr(it, true) }
        val dueLong = Dates.long(due.dueDate)
        val badgeText = when {
            due.daysLeft < 0 -> "${-due.daysLeft}d overdue"
            due.daysLeft == 0L -> "Due today"
            else -> "${due.daysLeft}d left"
        }
        val headline = when {
            due.daysLeft < 0 -> "Bill overdue by ${-due.daysLeft} day${if (due.daysLeft == -1L) "" else "s"}"
            due.daysLeft == 0L -> "Bill due today"
            due.daysLeft == 1L -> "Bill due tomorrow"
            else -> "Bill due in ${due.daysLeft} days"
        }

        val open = PendingIntent.getActivity(
            context, notificationId(c.id),
            Intent(context, MainActivity::class.java)
                .setData("cards://card/${c.id}".toUri())
                .putExtra(EXTRA_OPEN_CARD, c.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val banner = createBillBannerBitmap(
            card = c,
            badgeText = badgeText,
            isUrgent = due.daysLeft <= 3,
            dueDateText = dueLong,
            minDueText = min,
            totalDueText = total,
        )

        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setLargeIcon(createSmallCardBitmap(c))
            .setColor(c.color.toInt())
            .setSubText("${c.issuer} · •• ${c.last4}")
            .setContentTitle("${c.nickname} · $total")
            .setContentText("${c.issuer} · Due $dueLong")
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(banner)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(headline)
                    .setSummaryText("Your ${c.nickname} statement is ready for payment."),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Mark as paid", ActionReceiver.intent(context, ActionReceiver.ACTION_PAID, c.id, dueEpoch))
            .addAction(0, "Remind later", ActionReceiver.intent(context, ActionReceiver.ACTION_SNOOZE, c.id, dueEpoch))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(c.id), n)
    }

    fun showStatement(context: Context, card: Card, s: Statement) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val total = s.totalDuePaise?.let { Money.inr(it, true) } ?: "See statement"
        val min = s.minDuePaise?.takeIf { it > 0L }?.let { Money.inr(it, true) }
        val dueDate = s.dueEpochDay?.let { LocalDate.ofEpochDay(it) }
        val dueLong = dueDate?.let { Dates.long(it) } ?: "Check app"
        val daysLeft = dueDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
        val badgeText = when {
            daysLeft == null -> "New bill"
            daysLeft < 0 -> "${-daysLeft}d overdue"
            daysLeft == 0L -> "Due today"
            else -> "${daysLeft}d left"
        }
        val headline = when {
            daysLeft == null -> "New statement ready"
            daysLeft < 0 -> "Bill overdue by ${-daysLeft} day${if (daysLeft == -1L) "" else "s"}"
            daysLeft == 0L -> "Bill due today"
            daysLeft == 1L -> "Bill due tomorrow"
            else -> "Bill due in $daysLeft days"
        }

        val open = PendingIntent.getActivity(
            context, notificationId(card.id),
            Intent(context, MainActivity::class.java)
                .setData("cards://statement/${s.id}".toUri())
                .putExtra(EXTRA_OPEN_STATEMENT, s.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val banner = createBillBannerBitmap(
            card = card,
            badgeText = badgeText,
            isUrgent = daysLeft != null && daysLeft <= 3,
            dueDateText = dueLong,
            minDueText = min,
            totalDueText = total,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_STATEMENTS)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setLargeIcon(createSmallCardBitmap(card))
            .setColor(card.color.toInt())
            .setSubText("${card.issuer} · •• ${card.last4}")
            .setContentTitle("${card.nickname} · $total")
            .setContentText("${card.issuer} · Due $dueLong")
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(banner)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(headline)
                    .setSummaryText("Your ${card.nickname} statement is ready for payment."),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)

        if (s.dueEpochDay != null && (s.totalDuePaise ?: 0L) > 0L) {
            builder.addAction(0, "Mark as paid", ActionReceiver.intent(context, ActionReceiver.ACTION_PAID, card.id, s.dueEpochDay))
            builder.addAction(0, "Remind later", ActionReceiver.intent(context, ActionReceiver.ACTION_SNOOZE, card.id, s.dueEpochDay))
        }
        NotificationManagerCompat.from(context).notify(notificationId(card.id), builder.build())
    }

    /** Sends a realistic BigPictureStyle test notification matching Android 17 / Hello OS notification shade. */
    fun sendTestPreview(context: Context, sample: CardWithDue?): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        createChannel(context)
        val previewCard = sample?.card ?: Card(
            id = 999_001L,
            nickname = "Demo Card",
            issuer = "Sample Bank",
            network = "Visa",
            last4 = "4321",
            color = 0xFF9C6C19L,
            outstandingPaise = 339_662L,
        )
        val dueDate = sample?.due?.dueDate ?: LocalDate.of(2026, 12, 15)
        val daysLeft = sample?.due?.daysLeft ?: 2L
        val amountPaise = sample?.card?.outstandingPaise?.takeIf { it > 0L } ?: 339_662L
        val minPaise = (amountPaise / 20L).coerceAtLeast(10_000L)

        val total = Money.inr(amountPaise, true)
        val min = Money.inr(minPaise, true)
        val dueLong = Dates.long(dueDate)
        val badgeText = when {
            daysLeft < 0 -> "${-daysLeft}d overdue"
            daysLeft == 0L -> "Due today"
            else -> "${daysLeft}d left"
        }
        val headline = when {
            daysLeft < 0 -> "Bill overdue by ${-daysLeft} day${if (daysLeft == -1L) "" else "s"}"
            daysLeft == 0L -> "Bill due today"
            daysLeft == 1L -> "Bill due tomorrow"
            else -> "Bill due in $daysLeft days"
        }

        val open = PendingIntent.getActivity(
            context, 999_001,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val banner = createBillBannerBitmap(
            card = previewCard,
            badgeText = badgeText,
            isUrgent = daysLeft <= 3,
            dueDateText = dueLong,
            minDueText = min,
            totalDueText = total,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setLargeIcon(createSmallCardBitmap(previewCard))
            .setColor(previewCard.color.toInt())
            .setSubText("${previewCard.issuer} · Preview")
            .setContentTitle("${previewCard.nickname} · $total")
            .setContentText("${previewCard.issuer} · Due $dueLong")
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(banner)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(headline)
                    .setSummaryText("Your ${previewCard.nickname} statement is ready for payment."),
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(999_001, n)
        return true
    }

    /**
     * Renders the wide BigPictureStyle credit-card banner inside expanded Android notifications,
     * showing Bank Name, Card Nickname + last 4, Urgency Pill, Due Date, Min Due, and Due Amount.
     */
    private fun createBillBannerBitmap(
        card: Card,
        badgeText: String,
        isUrgent: Boolean,
        dueDateText: String,
        minDueText: String?,
        totalDueText: String,
    ): Bitmap {
        val w = 960
        val h = 440
        val bmp = createBitmap(w, h)
        val canvas = Canvas(bmp)

        val rect = RectF(10f, 10f, w - 10f, h - 10f)
        val radius = 42f

        val art = CardArts.resolve(card)
        val isLightCard = art?.light == true
        val gradientColors = when {
            art != null && !isLightCard && art.colors.size >= 2 -> art.colors.map { darken(it.toInt(), 0.78f) }.toIntArray()
            else -> intArrayOf(Color.rgb(34, 36, 42), Color.rgb(24, 25, 29))
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                gradientColors, null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // Subtle card-color glow accent in top-right
        val accentHex = art?.accent?.firstOrNull()?.toInt() ?: card.color.toInt()
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, Color.red(accentHex), Color.green(accentHex), Color.blue(accentHex))
        }
        canvas.drawCircle(w - 110f, 95f, 190f, glowPaint)

        // Subtle outer border rim
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.argb(46, 255, 255, 255)
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        // Top row: Circular bank badge + Bank Name + Card Nickname •• last4 + Status Pill
        val pad = 48f
        val emblemCenter = pad + 32f
        val emblemCy = pad + 38f
        val emblemBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 255, 255, 255)
        }
        canvas.drawCircle(emblemCenter, emblemCy, 32f, emblemBgPaint)
        val bankInitial = card.issuer.trim().take(2).uppercase()
        val emblemTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(bankInitial, emblemCenter, emblemCy + 8.5f, emblemTextPaint)

        val issuerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(204, 242, 242, 245)
            textSize = 25f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas.drawText(card.issuer, pad + 84f, pad + 26f, issuerPaint)

        val nickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 33f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("${card.nickname} •• ${card.last4}", pad + 84f, pad + 64f, nickPaint)

        // Top-right Urgency Pill
        val pillBg = if (isUrgent) Color.rgb(255, 218, 214) else Color.rgb(209, 247, 227)
        val pillInk = if (isUrgent) Color.rgb(147, 0, 10) else Color.rgb(6, 78, 43)
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pillInk
            textSize = 25f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pillTextW = pillTextPaint.measureText(badgeText)
        val pillW = pillTextW + 44f
        val pillRect = RectF(w - pad - pillW, pad + 8f, w - pad, pad + 60f)
        canvas.drawRoundRect(pillRect, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillBg })
        canvas.drawText(badgeText, pillRect.left + 22f, pillRect.top + 35f, pillTextPaint)

        // Hairline divider
        val divY = 212f
        canvas.drawLine(pad, divY, w - pad, divY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(42, 255, 255, 255)
            strokeWidth = 2f
        })

        // Bottom row: Due date | Optional Min due | Due amount
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 242, 242, 245)
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelY = 276f
        val valueY = 332f

        // Left: Due date
        canvas.drawText("Due date", pad, labelY, labelPaint)
        canvas.drawText(dueDateText, pad, valueY, valPaint)

        // Center: Minimum Amount Due (when available)
        if (!minDueText.isNullOrBlank()) {
            val midX = w * 0.41f
            canvas.drawText("Min due", midX, labelY, labelPaint)
            canvas.drawText(minDueText, midX, valueY, valPaint)
        }

        // Right: Total Due Amount (right-aligned large tabular headline)
        val rightLabelPaint = Paint(labelPaint).apply { textAlign = Paint.Align.RIGHT }
        val amtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Due amount", w - pad, labelY, rightLabelPaint)
        canvas.drawText(totalDueText, w - pad, 344f, amtPaint)

        return bmp
    }

    /** Crisp miniature card thumbnail for collapsed notification slot. */
    private fun createSmallCardBitmap(card: Card): Bitmap {
        val size = 192
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        val cardRect = RectF(6f, 37f, 186f, 155f)
        val art = CardArts.resolve(card)
        val stops = when {
            art != null && art.colors.size >= 2 -> art.colors.map { it.toInt() }.toIntArray()
            else -> intArrayOf(card.color.toInt(), darken(card.color.toInt(), 0.58f))
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(6f, 37f, 186f, 155f, stops, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(cardRect, 16f, 16f, bgPaint)

        val chipRect = RectF(24f, 71f, 58f, 95f)
        canvas.drawRoundRect(
            chipRect, 5f, 5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    chipRect.left, chipRect.top, chipRect.right, chipRect.bottom,
                    Color.rgb(246, 231, 180), Color.rgb(198, 158, 72), Shader.TileMode.CLAMP,
                )
            },
        )
        val inkColor = if (art?.light == true) Color.rgb(21, 23, 28) else Color.WHITE
        val last4Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("•• ${card.last4}", 24f, 137f, last4Paint)
        return bmp
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun showMissingStatement(
        context: Context,
        card: Card,
        statementDate: LocalDate,
        gmailConnected: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        createChannel(context)

        val dateLabel = Dates.short(statementDate)
        val body = if (gmailConnected) {
            "No statement fetched yet for $dateLabel cycle. Tap to upload the PDF statement."
        } else {
            "Your $dateLabel statement should be ready. Tap to upload the PDF statement."
        }

        val notifId = missingStatementNotificationId(card.id)
        val open = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java)
                .setData("cards://card/${card.id}?upload=true".toUri())
                .putExtra(EXTRA_OPEN_CARD, card.id)
                .putExtra(EXTRA_OPEN_UPLOAD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_STATEMENTS)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setLargeIcon(createSmallCardBitmap(card))
            .setColor(card.color.toInt())
            .setSubText("${card.issuer} · •• ${card.last4}")
            .setContentTitle("Upload ${card.nickname} statement")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notifId, n)
        return true
    }

    fun cancel(context: Context, cardId: Long) {
        val mgr = NotificationManagerCompat.from(context)
        mgr.cancel(notificationId(cardId))
        mgr.cancel(missingStatementNotificationId(cardId))
    }
}
