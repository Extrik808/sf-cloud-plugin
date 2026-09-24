package dev.sfcloud.core

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object SfNotifier {
    private const val GROUP = "SF Cloud"

    fun info(project: Project?, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.INFORMATION, actions)

    fun warn(project: Project?, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.WARNING, actions)

    fun error(project: Project?, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, title, content, NotificationType.ERROR, actions)

    private fun notify(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        actions: Array<out NotificationAction>,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(title, content, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }
}
