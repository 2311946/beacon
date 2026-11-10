package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.PROACTIVE_MESSAGE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.NotificationUtil
import kotlin.uuid.Uuid

/** 仅本次入队的唤醒回复由 Worker 通知，不能从旧聊天历史推断。 */
internal fun hasDedicatedWakeupNotification(parts: List<UIMessagePart>): Boolean = parts.any {
    (it.metadata?.get("beacon_event") as? JsonPrimitive)?.content in
        setOf("autonomous_wakeup", "autonomous_task")
}

/** 通知身份与聊天头像/模型展示设置无关。 */
internal fun chatNotificationSenderName(assistant: Assistant, fallback: String): String =
    assistant.name.ifBlank { fallback }

/** 按 Unicode 码点截取，避免把 emoji 的代理对截成半个字符。 */
internal fun wakeupNotificationPreview(text: String): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    return normalized.substring(0, normalized.offsetByCodePoints(0, minOf(50, normalized.codePointCount(0, normalized.length))))
}

internal fun wakeupNotificationTag(assistantId: String): String = "autonomous_wakeup:$assistantId"

@SuppressLint("MissingPermission")
internal suspend fun postWakeupNotification(context: Context, conversationId: Uuid, assistant: Assistant, text: String) {
    if (!NotificationUtil.hasNotificationPermission(context)) return
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val name = assistant.name.ifBlank { "角色" }
    val avatar = loadWakeupAvatar(context, assistant.avatar, name)
    val sender = Person.Builder().setName(name).setKey(assistant.id.toString()).apply {
        avatar?.let { setIcon(IconCompat.createWithBitmap(it)) }
    }.build()
    val preview = wakeupNotificationPreview(text)
    // Intent 的 data 参与 PendingIntent 身份判定，避免 hash 冲突让不同角色打开错误会话。
    val intent = conversationIntent(context, conversationId.toString()).apply {
        data = Uri.Builder().scheme("beacon").authority("wakeup")
            .appendPath(assistant.id.toString()).appendPath(conversationId.toString()).build()
    }
    val pendingIntent = PendingIntent.getActivity(
        context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, PROACTIVE_MESSAGE_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_rikkahub)
        .setContentTitle(name)
        .setContentText(preview)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setOnlyAlertOnce(false)
        // 每个角色单独的通知标识及分组键，不创建跨角色汇总通知。
        .setGroup(wakeupNotificationTag(assistant.id.toString()))
        .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("我").build())
            .setGroupConversation(false)
            .addMessage(preview, System.currentTimeMillis(), sender))
        .apply { avatar?.let { setLargeIcon(it) } }
        .build()
    try {
        manager.notify(wakeupNotificationTag(assistant.id.toString()), 0, notification)
    } catch (e: SecurityException) {
        Log.w("AutonomousWakeup", "Notification permission revoked before submission", e)
    }
}

private suspend fun loadWakeupAvatar(context: Context, avatar: Avatar, name: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val bitmap = when (avatar) {
            is Avatar.Image -> withTimeoutOrNull(3_000) {
                context.imageLoader.execute(ImageRequest.Builder(context).data(avatar.url)
                    .size(192, 192).allowHardware(false).build()).image?.toBitmap()
            }
            else -> null
        }
        val size = 192
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(96f, 96f, 96f, paint.apply { color = Color.rgb(218, 232, 223) })
        if (bitmap != null) {
            val shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            val scale = maxOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - bitmap.width * scale) / 2f, (size - bitmap.height * scale) / 2f)
            })
            paint.shader = shader
            canvas.drawCircle(96f, 96f, 96f, paint)
        } else {
            paint.color = Color.rgb(38, 70, 49)
            paint.textSize = if (avatar is Avatar.Emoji) 100f else 82f
            paint.textAlign = Paint.Align.CENTER
            val label = if (avatar is Avatar.Emoji) avatar.content else name.take(1).uppercase()
            canvas.drawText(label, 96f, 96f - (paint.ascent() + paint.descent()) / 2, paint)
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 头像失败不阻止消息通知。
        Log.w("AutonomousWakeup", "Unable to load notification avatar", e)
        null
    }
}