package dev.sfcloud

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sfcloud.lang.ApexNamedElement
import dev.sfcloud.lang.ApexUsages
import dev.sfcloud.lang.ApexUsagesCodeVisionProvider

class ApexUsagesTest : BasePlatformTestCase() {
    fun testHintWording() {
        assertEquals("no usages", ApexUsages.hint(0))
        assertEquals("1 usage", ApexUsages.hint(1))
        assertEquals("3 usages", ApexUsages.hint(3))
        assertEquals("${ApexUsages.LIMIT}+ usages", ApexUsages.hint(ApexUsages.LIMIT))
    }

    fun testCodeVisionAcceptsOnlyMemberNames() {
        val file = myFixture.configureByText(
            "AccountService.cls",
            """
            public class AccountService {
                private Integer total;
                public Integer size { get; set; }
                public void load(String name) {
                    helper(name);
                }
                private void helper(String value) {}
            }
            """.trimIndent(),
        )
        val provider = ApexUsagesCodeVisionProvider()
        assertTrue(provider.acceptsFile(file))
        val accepted = PsiTreeUtil.collectElements(file) { it is ApexNamedElement }
            .filter { provider.acceptsElement(it) }
            .map { it.text to file.text.substring(0, it.textRange.startOffset).count { c -> c == '\n' } }
        assertEquals(
            listOf("AccountService" to 0, "total" to 1, "size" to 2, "load" to 3, "helper" to 6),
            accepted,
        )
    }
}
