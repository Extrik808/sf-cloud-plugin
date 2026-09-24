package dev.sfcloud

import dev.sfcloud.org.ScratchOrgCommands
import dev.sfcloud.org.ScratchOrgRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScratchOrgCommandsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val request = ScratchOrgRequest(
        devHub = "DevHubPROD",
        alias = "ABC-100-sc",
        definitionFile = "config/project-scratch-def.json",
        durationDays = 30,
        useForProject = true,
        setCliDefault = false,
        noNamespace = false,
        noAncestors = false,
        platformCliSignup = false,
        generatePassword = false,
        pushSource = true,
        permissionSets = emptyList(),
        openAfterCreate = false,
    )

    @Test
    fun createPassesTheDialogChoicesToTheCli() {
        assertEquals(
            listOf(
                "org", "create", "scratch", "--target-dev-hub", "DevHubPROD", "--alias", "ABC-100-sc",
                "--definition-file", "config/project-scratch-def.json", "--duration-days", "30", "--wait", "60",
            ),
            ScratchOrgCommands.create(request),
        )
        val flagged = ScratchOrgCommands.create(request.copy(setCliDefault = true, noNamespace = true, noAncestors = true))
        assertEquals(listOf("--set-default", "--no-namespace", "--no-ancestors"), flagged.takeLast(3))
    }

    @Test
    fun platformCliSignupSetsTheSignupEnvironment() {
        assertTrue(ScratchOrgCommands.environment(request).isEmpty())
        assertEquals(
            mapOf(
                "SF_SCRATCH_SIGNUP_CONNECTED_APP" to "PlatformCLI",
                "SF_SCRATCH_SIGNUP_CALLBACK_URL" to "http://localhost:1717/OauthRedirect",
            ),
            ScratchOrgCommands.environment(request.copy(platformCliSignup = true)),
        )
    }

    @Test
    fun permissionSetsAreSplitAndPassedOneFlagEach() {
        val names = ScratchOrgCommands.splitPermissionSets(" App_Admin, App_Support;App_Api \n")
        assertEquals(listOf("App_Admin", "App_Support", "App_Api"), names)
        assertEquals(
            listOf("org", "assign", "permset", "--target-org", "ABC-100-sc", "--name", "App_Admin", "--name", "App_Support", "--name", "App_Api"),
            ScratchOrgCommands.assignPermissionSets("ABC-100-sc", names),
        )
        assertEquals(listOf("org", "delete", "scratch", "--target-org", "ABC-100-sc", "--no-prompt"), ScratchOrgCommands.delete("ABC-100-sc"))
    }

    @Test
    fun aliasIsSuggestedFromTheTicketInTheBranch() {
        assertEquals("ABC-107-sc", ScratchOrgCommands.suggestedAlias("feature/ABC-107", "SampleProject"))
        assertEquals("packaging-sc", ScratchOrgCommands.suggestedAlias("packaging", "SampleProject"))
        assertEquals("SampleProject-sc", ScratchOrgCommands.suggestedAlias(null, "SampleProject"))
        assertEquals("My-App-sc", ScratchOrgCommands.suggestedAlias("HEAD", "My App"))
    }

    @Test
    fun currentBranchIsReadFromGitHead() {
        val root = temp.newFolder("repo")
        assertNull(ScratchOrgCommands.currentBranch(root))
        root.resolve(".git").mkdirs()
        root.resolve(".git/HEAD").writeText("ref: refs/heads/feature/ABC-107\n")
        assertEquals("feature/ABC-107", ScratchOrgCommands.currentBranch(root))
    }
}
