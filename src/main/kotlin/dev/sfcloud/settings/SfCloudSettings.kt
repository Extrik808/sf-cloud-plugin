package dev.sfcloud.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class SfCloudAppState : BaseState() {
    var sfPath by string("")
    var apexLspEnabled by property(true)
    var apexLspJarPath by string("")
    var apexLspJavaPath by string("")
    var apexLspMaxHeapMb by property(2048)
    var apexSemanticErrors by property(false)
    var lwcLspEnabled by property(true)
    var autoInstallServers by property(true)
    var apexCompletion by property(true)
    var lwcCompletion by property(true)
    var bundleEditorTabs by property(true)
    var nodePath by string("")
}

@Service(Service.Level.APP)
@State(name = "SfCloudSettings", storages = [Storage("sf-cloud.xml", roamingType = RoamingType.DISABLED)])
class SfCloudSettings : SimplePersistentStateComponent<SfCloudAppState>(SfCloudAppState()) {
    companion object {
        fun getInstance(): SfCloudSettings = service()
    }
}

class SfCloudProjectState : BaseState() {
    var targetOrg by string("")
    var deployOnSave by property(true)
    var ostAutoUpdate by property(true)
    var testWaitMinutes by property(30)
    var showCoverage by property(true)
    var recentOrgs by list<String>()
    var rememberRecentOrgs by property(true)
    var maxRecentOrgs by property(8)
    var scratchOrg by property(ScratchOrgState())
}

class ScratchOrgState : BaseState() {
    var devHub by string("")
    var definitionFile by string("config/project-scratch-def.json")
    var durationDays by property(7)
    var useForProject by property(true)
    var setCliDefault by property(false)
    var noNamespace by property(false)
    var noAncestors by property(false)
    var platformCliSignup by property(false)
    var generatePassword by property(false)
    var pushSource by property(true)
    var permissionSets by string("")
    var openAfterCreate by property(false)
}

@Service(Service.Level.PROJECT)
@State(name = "SfCloudProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SfCloudProjectSettings : SimplePersistentStateComponent<SfCloudProjectState>(SfCloudProjectState()) {
    companion object {
        fun getInstance(project: Project): SfCloudProjectSettings = project.service()
    }
}
