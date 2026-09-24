package dev.sfcloud.ai

import com.intellij.ml.llm.nextEdits.backend.settings.lang.NextEditLanguageSettings

class SalesforceNextEditLanguages : NextEditLanguageSettings.Group() {
    override val displayName: String = "Salesforce (Apex, SOQL, SOSL)"

    override val id: String = "sf-cloud.salesforce"

    override val isEnabledByDefault: Boolean = true

    override val isExperimental: Boolean = false
}
