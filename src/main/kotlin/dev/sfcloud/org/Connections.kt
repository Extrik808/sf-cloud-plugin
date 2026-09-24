package dev.sfcloud.org

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import dev.sfcloud.lang.SfCloudIcons
import dev.sfcloud.settings.SfCloudProjectSettings
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JList
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

sealed interface ConnectionItem {
    val searchText: String

    data class Header(val text: String) : ConnectionItem {
        override val searchText: String get() = ""
    }

    data class Org(val org: SfOrg) : ConnectionItem {
        override val searchText: String get() = "${org.displayName}::${org.username}"
    }

    data class Missing(val key: String) : ConnectionItem {
        override val searchText: String get() = key
    }

    data object Default : ConnectionItem {
        override val searchText: String get() = ""
    }
}

object Connections {
    const val PROJECT = "Project Connections"
    const val RECENT = "Recent Connections"
    const val GLOBAL = "Global Connections"
    const val MAX_RECENT = 20
    private const val USERNAME_WIDTH = 40

    fun icon(org: SfOrg?): Icon = when {
        org == null -> AllIcons.Actions.OfflineMode
        !org.connected -> AllIcons.Actions.OfflineMode
        org.kind == OrgKind.SCRATCH -> SfCloudIcons.ScratchOrg
        else -> AllIcons.General.Web
    }

    fun items(project: Project): List<ConnectionItem> {
        val service = OrgService.getInstance(project)
        val orgs = service.orgs
        val projectOrg = service.targetOrg
        val settings = SfCloudProjectSettings.getInstance(project).state
        val recentKeys = if (settings.rememberRecentOrgs) settings.recentOrgs else emptyList()
        val recent = recentKeys.mapNotNull { key -> service.find(key) }.filter { it != projectOrg }.distinct()
        val global = orgs.filter { it != projectOrg && it !in recent }
        return buildList {
            if (projectOrg != null) {
                add(ConnectionItem.Header(PROJECT))
                add(ConnectionItem.Org(projectOrg))
            }
            if (recent.isNotEmpty()) {
                add(ConnectionItem.Header(RECENT))
                recent.forEach { add(ConnectionItem.Org(it)) }
            }
            if (global.isNotEmpty()) {
                add(ConnectionItem.Header(GLOBAL))
                global.forEach { add(ConnectionItem.Org(it)) }
            }
        }
    }

    fun recordUsage(project: Project, key: String?) {
        val state = SfCloudProjectSettings.getInstance(project).state
        if (key.isNullOrBlank() || !state.rememberRecentOrgs) return
        val updated = (listOf(key) + state.recentOrgs.filter { it != key }).take(state.maxRecentOrgs.coerceIn(1, MAX_RECENT))
        state.recentOrgs.clear()
        state.recentOrgs.addAll(updated)
    }

    fun shortenUsername(username: String, width: Int = USERNAME_WIDTH): String {
        if (username.length <= width) return username
        val keepEnd = username.length - username.lastIndexOf('.').coerceAtLeast(username.length - width / 2)
        val tail = username.takeLast(keepEnd.coerceIn(4, width / 2))
        val head = username.take(width - tail.length - 1)
        return "$head…$tail"
    }

    fun render(renderer: ColoredListCellRenderer<*>, item: ConnectionItem?, defaultLabel: String? = null) {
        when (item) {
            null -> {
                renderer.append("<invalid>", SimpleTextAttributes.ERROR_ATTRIBUTES)
                renderer.toolTipText = "No connection found. Please create or select a connection."
            }
            is ConnectionItem.Header -> renderer.append(item.text, SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            is ConnectionItem.Default -> {
                renderer.icon = AllIcons.Nodes.Project
                renderer.append(defaultLabel ?: "<Project Connection>", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                renderer.toolTipText = "The connection selected for the project in the status bar."
            }
            is ConnectionItem.Missing -> {
                renderer.icon = AllIcons.Actions.OfflineMode
                renderer.append(item.key, SimpleTextAttributes.ERROR_ATTRIBUTES)
                renderer.toolTipText = "No connection found with name '${item.key}'. Please authorize an org with that alias or select another connection."
            }
            is ConnectionItem.Org -> {
                val org = item.org
                renderer.icon = icon(org)
                renderer.append(org.displayName, if (org.connected) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.ERROR_ATTRIBUTES)
                if (org.alias != null) renderer.append(" (${shortenUsername(org.username)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (org.kind == OrgKind.PRODUCTION) renderer.append(" production", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                renderer.toolTipText = org.username
            }
        }
    }
}

class ConnectionComboBox(
    private val project: Project,
    parent: Disposable,
    initialKey: String?,
    private val allowDefault: Boolean = false,
    private val onChange: (String?) -> Unit,
) : ComboBox<ConnectionItem>() {
    private val items = object : DefaultComboBoxModel<ConnectionItem>() {
        override fun setSelectedItem(anObject: Any?) {
            if (anObject is ConnectionItem.Header) return
            super.setSelectedItem(anObject)
        }
    }
    private var current: String? = initialKey?.takeIf { it.isNotBlank() }
    private var reloading = false

    init {
        model = items
        toolTipText = "The Salesforce connection."
        renderer = object : ColoredListCellRenderer<ConnectionItem>() {
            override fun customizeCellRenderer(list: JList<out ConnectionItem>, value: ConnectionItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
                Connections.render(this, value, defaultLabel())
            }
        }
        ComboboxSpeedSearch.installOn(this).apply { setClearSearchOnNavigateNoMatch(true) }
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = reload()

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = Unit

            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
        addActionListener {
            if (reloading) return@addActionListener
            val key = when (val item = selectedItem) {
                is ConnectionItem.Org -> item.org.key
                is ConnectionItem.Missing -> item.key
                else -> null
            }
            if (key == current) return@addActionListener
            current = key
            Connections.recordUsage(project, key)
            onChange(key)
        }
        project.messageBus.connect(parent).subscribe(OrgListener.TOPIC, OrgListener { reload() })
        OrgService.getInstance(project).ensureLoaded()
        reload(notify = false)
    }

    val selectedKey: String? get() = current

    val selectedOrg: SfOrg? get() = current?.let { OrgService.getInstance(project).find(it) }

    fun select(key: String?) {
        current = key?.takeIf { it.isNotBlank() }
        reload()
    }

    private fun defaultLabel(): String {
        val name = OrgService.getInstance(project).targetOrg?.displayName ?: OrgService.getInstance(project).targetOrgKey ?: "none"
        return "<Project Connection: $name>"
    }

    private fun reload(notify: Boolean = true) {
        reloading = true
        try {
            val list = Connections.items(project).toMutableList()
            if (allowDefault) list.add(0, ConnectionItem.Default)
            val key = current
            if (current == null && !allowDefault) current = OrgService.getInstance(project).targetOrgKey
            val service = OrgService.getInstance(project)
            val selected: ConnectionItem? = when {
                current == null && allowDefault -> ConnectionItem.Default
                current == null -> null
                else -> list.firstOrNull { it is ConnectionItem.Org && (it.org.key == current || it.org.username == current) }
                    ?: service.find(current!!)?.let { ConnectionItem.Org(it) }
                    ?: ConnectionItem.Missing(current!!)
            }
            if (selected != null && selected !in list) list.add(selected)
            items.removeAllElements()
            items.addAll(list)
            items.selectedItem = selected
            if (notify && key == null && current != null) onChange(current)
        } finally {
            reloading = false
        }
    }
}
