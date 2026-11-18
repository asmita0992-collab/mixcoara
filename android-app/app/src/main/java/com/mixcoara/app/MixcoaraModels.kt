package com.mixcoara.app

import com.mixcoara.app.ui.theme.MixcoaraThemeId
import org.json.JSONObject

data class VaultConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val folder: String = "Mixcoara",
)

data class VaultEntry(
    val name: String,
    val isDirectory: Boolean,
    val id: String,
    val size: Long,
    val parentId: String,
)

data class BreadcrumbNode(val id: String, val name: String)

data class TransferProgress(
    val opId: String = "",
    val phase: String = "",
    val state: String = "",
    val currentItem: String = "",
    val fraction: Float = 0f,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val foldersDone: Int = 0,
    val foldersTotal: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val elapsedSec: Double = 0.0,
    val prepElapsedSec: Double = 0.0,
    val uploadElapsedSec: Double = 0.0,
    val etaSec: Double? = null,
    val isCancelled: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
    val result: JSONObject? = null,
)

data class TransferSummary(
    val title: String,
    val filesCount: Int,
    val foldersCount: Int,
    val bytesCount: Long,
    val elapsedSec: Double,
    val prepElapsedSec: Double = 0.0,
    val destination: String,
    val errors: List<String> = emptyList(),
)

enum class AppTab { EXPLORER, LOCAL_VAULT, SECURITY, LOGS, ABOUT }
enum class AppScreen { CHECKING, CONNECTION, UNLOCK, CREATE, RECOVERY_KEY, VAULT }

data class MixcoaraUiState(
    val screen: AppScreen = AppScreen.CHECKING,
    val activeTab: AppTab = AppTab.EXPLORER,
    val config: VaultConfig = VaultConfig(),
    val busy: Boolean = true,
    val message: String = "Comprobando el motor criptográfico…",
    val error: String? = null,
    val vaultExists: Boolean = false,
    val sessionId: String? = null,
    val recoveryKey: String? = null,
    val entries: List<VaultEntry> = emptyList(),
    val breadcrumbs: List<BreadcrumbNode> = emptyList(),
    val selectedEntryIds: Set<String> = emptySet(),
    val activeProgress: TransferProgress? = null,
    val transferSummary: TransferSummary? = null,
    val cryptoReady: Boolean = false,
    val logText: String = "",
    val biometricEnabled: Boolean = false,
    val vaultContentVisible: Boolean = false,
    val themeId: MixcoaraThemeId = MixcoaraThemeId.MIXCO,
)