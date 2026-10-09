package com.openodoubao.assistant

import android.Manifest
import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.openodoubao.assistant.core.*
import com.openodoubao.assistant.model.CloudModels
import com.openodoubao.assistant.model.ModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class OfflineTaskTest {
    @Test fun realNetworkFailureReachesTaskMessageWithoutPhoneActions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineCheck") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_NETWORK_STATE)
        try {
            assertTrue("Disconnect all networks before this opt-in test",
                instrument.targetContext.getSystemService(ConnectivityManager::class.java).allNetworks.isEmpty())
        } finally { instrument.uiAutomation.dropShellPermissionIdentity() }
        val phone = object : Phone {
            override fun canLaunch(packageName: String) = true
            override suspend fun observe(): Screen = error("Offline task must not inspect the phone")
            override suspend fun screenshot(screen: Screen): String = error("Offline task must not capture the screen")
            override suspend fun execute(action: Action, expected: Screen?, expectedImage: String?): Boolean =
                error("Offline task must not operate the phone")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val models = CloudModels(ModelConfig("https://example.com/v1", "offline-test", "offline-test",
                "https://example.com/v1", "offline-test", "offline-test"))
            val task = TaskController(scope, phone, models)
            withContext(Dispatchers.Main) { task.start("离线错误检查", setOf(instrument.targetContext.packageName)) }
            val result = withTimeout(20_000) { task.state.first { it.status == TaskStatus.FAILED } }
            assertTrue(result.message, result.message.contains("规划") && result.message.contains("网络"))
            assertEquals(0, result.steps)
            instrument.sendStatus(0, android.os.Bundle().apply { putString("stream", "Offline task message: ${result.message}\n") })
        } finally { scope.cancel() }
    }
}
