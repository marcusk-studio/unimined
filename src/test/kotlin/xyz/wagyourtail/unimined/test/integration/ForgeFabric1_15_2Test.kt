package xyz.wagyourtail.unimined.test.integration

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.util.runTestProject

class ForgeFabric1_15_2Test {
    @Test
    fun test_forge_fabric_1_15_2() {
        val result = runTestProject("1.15.2-Forge-Fabric")
        result.task(":build")?.outcome?.let {
            if (it != TaskOutcome.SUCCESS) throw Exception("build failed")
        } ?: throw Exception("build failed")
    }
}