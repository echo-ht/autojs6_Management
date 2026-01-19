package org.autojs.autojs.network

import android.accessibilityservice.AccessibilityService as AndroidAccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.Display
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.autojs.autojs.AutoJs
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.core.accessibility.AccessibilityService as AutoJsAccessibilityService
import org.autojs.autojs.core.automator.GlobalActionAutomator
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.execution.ExecutionConfig
import org.autojs.autojs.execution.ScriptExecution
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.pio.PFiles
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.AppUtils
import org.autojs.autojs.runtime.api.Device
import org.autojs.autojs.script.ScriptSource
import org.autojs.autojs.timing.IntentTask
import org.autojs.autojs.timing.TimedTask
import org.autojs.autojs.timing.TimedTaskManager
import org.autojs.autojs.external.fileprovider.AppFileProvider
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs.util.IntentUtils.ToastExceptionHolder
import org.autojs.autojs.util.RootUtils
import org.autojs.autojs.external.receiver.DynamicBroadcastReceivers
import org.autojs.autojs.util.WorkingDirectoryUtils
import org.autojs.autojs6.BuildConfig
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants
import org.joda.time.LocalDateTime
import org.joda.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ManagementPlatformClient {

    private const val TAG = "ManagementPlatformClient"

    private val appContext get() = GlobalAppContext.get()

    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var heartbeatFuture: ScheduledFuture<*>? = null

    @Volatile
    private var reconnectFuture: ScheduledFuture<*>? = null

    private data class ScriptLogRange(var startIndex: Int, var endIndex: Int? = null)

    private const val GLOBAL_LOG_ID = "__ALL__"
    private const val DEFAULT_LOG_TAIL_LINES = 200

    private val scriptLogRanges = mutableMapOf<String, ScriptLogRange>()

    @Volatile
    private var lastScreenshotImageWidth: Int = 0

    @Volatile
    private var lastScreenshotImageHeight: Int = 0

    @Volatile
    private var lastScreenWidth: Int = 0

    @Volatile
    private var lastScreenHeight: Int = 0

    fun connectIfConfigured() {
        val address = Pref.getManagementPlatformServerAddress().trim()
        val secret = Pref.getManagementPlatformSecret().trim()
        if (address.isEmpty() || secret.isEmpty()) {
            return
        }
        connect(address)
    }

    @Synchronized
    fun connect(address: String) {
        // 连接前取消已有的重连任务，避免并发创建多个 WebSocket
        reconnectFuture?.cancel(false)
        reconnectFuture = null

        val url = buildWebSocketUrl(address)
        val request = Request.Builder().url(url).build()
        webSocket?.cancel()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "onOpen")
                sendDeviceInfo()
                sendScriptListSafe()
                sendInstalledAppsSafe()
                startHeartbeat()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosed: $code $reason")
                stopHeartbeat()
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "onFailure", t)
                stopHeartbeat()
                scheduleReconnect()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }
        })
    }

    private fun buildWebSocketUrl(address: String): String {
        val trimmed = address.trim()
        val deviceId = deviceId()
        val secret = Pref.getManagementPlatformSecret().trim()

        fun appendQuery(base: String): String {
            val encodedDeviceId = URLEncoder.encode(deviceId, "UTF-8")
            val encodedSecret = URLEncoder.encode(secret, "UTF-8")
            val sep = if (base.contains("?")) "&" else "?"
            val withDevice = base + sep + "deviceId=" + encodedDeviceId
            return if (secret.isNotEmpty()) "$withDevice&matchCode=$encodedSecret" else withDevice
        }

        val base = when {
            trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "ws://")
            trimmed.startsWith("https://") -> trimmed.replaceFirst("https://", "wss://")
            else -> "ws://$trimmed/ws/device"
        }
        return appendQuery(base)
    }

    private fun sendDeviceInfo() {
        val payload = JSONObject()
        val id = deviceId()
        payload.put("deviceId", id)
        payload.put("model", Build.MODEL)
        payload.put("androidVersion", Build.VERSION.RELEASE)
        payload.put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        payload.put("manufacturer", Build.MANUFACTURER)

        // Add status fields
        collectDeviceStatus(payload)

        val extra = JSONObject()
        extra.put("brand", Build.BRAND)
        extra.put("sdkInt", Build.VERSION.SDK_INT)
        val secret = Pref.getManagementPlatformSecret().trim()
        if (secret.isNotEmpty()) {
            extra.put("matchCode", secret)
        }
        payload.put("extra", extra)

        send("DEVICE_INFO", payload)
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = scheduler.scheduleAtFixedRate(
            { sendHeartbeat() },
            5L, // initial delay in seconds
            15L, // heartbeat interval in seconds
            TimeUnit.SECONDS,
        )
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun sendHeartbeat() {
        val payload = JSONObject()
        payload.put("timestamp", System.currentTimeMillis())

        collectDeviceStatus(payload)

        Log.d(TAG, "Sending Heartbeat: $payload")
        send("HEARTBEAT", payload)
    }

    private fun collectDeviceStatus(payload: JSONObject) {
        try {
            // Battery
            val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale > 0) {
                    payload.put("battery", (level * 100 / scale.toFloat()).toInt())
                }
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                payload.put("isCharging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }

            // Volume (Music stream)
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                // payload.put("maxVolume", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                payload.put("volume", currentVolume)
            }

            // Brightness
            try {
                val brightness = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                // Brightness is usually 0-255
                payload.put("brightness", (brightness * 100 / 255.0).toInt())
            } catch (e: Exception) {
                // Ignore
            }

            // Bluetooth
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null) {
                    payload.put("bluetoothEnabled", adapter.isEnabled)
                }
            } catch (e: Exception) {
                // Ignore
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect device status", e)
        }
    }

    private fun scheduleReconnect() {
        val address = Pref.getManagementPlatformServerAddress().trim()
        val secret = Pref.getManagementPlatformSecret().trim()
        if (address.isEmpty() || secret.isEmpty()) {
            return
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule(
            {
                try {
                    connect(address)
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect failed", e)
                }
            },
            10L,
            TimeUnit.SECONDS,
        )
    }

    fun send(type: String, payload: JSONObject) {
        val ws = webSocket ?: return
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("payload", payload)
        ws.send(obj.toString())
    }

     /**
      * Lightweight connection test used from settings.
      */
    fun testConnection(callback: (Boolean, String?) -> Unit) {
        val address = Pref.getManagementPlatformServerAddress().trim()
        val secret = Pref.getManagementPlatformSecret().trim()
        if (address.isEmpty()) {
            callback(false, "Server address is empty")
            return
        }
        if (secret.isEmpty()) {
            callback(false, "Secret is empty")
            return
        }

        val baseUrl = buildWebSocketUrl(address)
        // 测试连接使用 mode=test，不参与设备在线状态与心跳逻辑
        val testUrl = if (baseUrl.contains("?")) "$baseUrl&mode=test" else "$baseUrl?mode=test"
        val request = Request.Builder().url(testUrl).build()

        httpClient.newWebSocket(request, object : WebSocketListener() {
            @Volatile
            private var done = false

            override fun onOpen(ws: WebSocket, response: Response) {
                if (done) return
                done = true
                callback(true, null)
                ws.close(1000, "test finished")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (done) return
                done = true
                callback(false, "code=$code, reason=$reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (done) return
                done = true
                callback(false, t.message)
            }
        })
    }

    private fun handleServerMessage(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type")
            val payloadAny = obj.opt("payload")
            val payload = when (payloadAny) {
                is JSONObject -> payloadAny
                else -> JSONObject()
            }
            when (type) {
                "REQUEST_SCRIPT_LIST" -> sendScriptListSafe()
                "PUSH_SCRIPT" -> handlePushScript(payload)
                "RUN_SCRIPT" -> handleRunScript(payload)
                "REQUEST_SCREENSHOT" -> handleRequestScreenshot(payload)
                "TOUCH_EVENT" -> handleTouchEvent(payload)
                "DEVICE_ACTION" -> handleDeviceAction(payload)
                "REQUEST_RUNNING_SCRIPTS" -> sendRunningScriptsSafe()
                "REQUEST_SCHEDULED_SCRIPTS" -> sendScheduledScriptsSafe()
                "REQUEST_INSTALLED_APPS" -> sendInstalledAppsSafe()
                "REQUEST_LOG_TAIL" -> handleRequestLogTail(payload)
                "REQUEST_SCRIPT_CONTENT" -> handleRequestScriptContent(payload)
                "UPDATE_SCRIPT_CONTENT" -> handleUpdateScriptContent(payload)
                "DELETE_SCRIPT" -> handleDeleteScript(payload)
                "CREATE_FOLDER" -> handleCreateFolder(payload)
                "CREATE_INTENT_TASK" -> handleCreateIntentTask(payload)
                "CREATE_TIMED_TASK" -> handleCreateTimedTask(payload)
                "DELETE_SCHEDULED_TASK" -> handleDeleteScheduledTask(payload)
                "INSTALL_APK" -> handleInstallApk(payload)
                else -> Log.w(TAG, "Unknown server message type: $type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle server message", e)
        }
    }

    private fun buildHttpBaseUrl(address: String): String {
        val trimmed = address.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("ws://") -> trimmed.replaceFirst("ws://", "http://")
            trimmed.startsWith("wss://") -> trimmed.replaceFirst("wss://", "https://")
            else -> "http://$trimmed"
        }
    }

    private fun scriptsRootDir(): File = File(WorkingDirectoryUtils.path)

    private data class ScriptSummary(
        val id: String,
        val name: String,
        val path: String,
        val size: Long,
        val updatedAt: Long,
    )

    private fun collectScriptsAndFolders(): Pair<List<ScriptSummary>, List<String>> {
        val root = scriptsRootDir()
        val scripts = mutableListOf<ScriptSummary>()
        val folders = mutableListOf<String>()

        fun scan(dir: File) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.isDirectory) {
                    val relPath = try {
                        root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                    } catch (_: Exception) {
                        f.name
                    }
                    folders.add(relPath)
                    scan(f)
                } else {
                    val scriptFile = ScriptFile(f)
                    val type = scriptFile.type
                    if (type == ScriptFile.TYPE_JAVASCRIPT || type == ScriptFile.TYPE_AUTO) {
                        val id = f.absolutePath
                        val name = scriptFile.name
                        val relPath = try {
                            root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                        } catch (_: Exception) {
                            f.name
                        }
                        scripts.add(
                            ScriptSummary(
                                id = id,
                                name = name,
                                path = relPath,
                                size = f.length(),
                                updatedAt = f.lastModified(),
                            ),
                        )
                    }
                }
            }
        }
        if (root.exists() && root.isDirectory) {
            scan(root)
        }
        return Pair(scripts, folders)
    }

    private fun sendScriptListSafe() {
        try {
            val (scripts, folders) = collectScriptsAndFolders()
            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            
            val scriptArr = JSONArray()
            for (s in scripts) {
                val o = JSONObject()
                o.put("id", s.id)
                o.put("name", s.name)
                o.put("path", s.path)
                o.put("size", s.size)
                o.put("updatedAt", s.updatedAt)
                scriptArr.put(o)
            }
            payload.put("scripts", scriptArr)

            val folderArr = JSONArray()
            for (f in folders) {
                folderArr.put(f)
            }
            payload.put("folders", folderArr)

            send("SCRIPT_LIST", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send script list", e)
        }
    }

    private fun sendInstalledAppsSafe() {
        try {
            val context = appContext
            val pm = context.packageManager
            val apps = AppUtils.getInstalledApplications(context)
            val arr = JSONArray()
            for (app in apps) {
                val obj = JSONObject()
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                obj.put("packageName", app.packageName)
                obj.put("name", label)

                val versionInfo = AppUtils.getInstalledVersionInfo(app.packageName)
                if (versionInfo != null) {
                    obj.put("versionName", versionInfo.versionName)
                    obj.put("versionCode", versionInfo.versionCode)
                }

                obj.put("targetSdk", app.targetSdkVersion)
                val isSystem =
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                obj.put("isSystem", isSystem)

                arr.put(obj)
            }

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("apps", arr)
            send("INSTALLED_APPS", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send installed apps", e)
        }
    }

    private fun handleCreateFolder(payload: JSONObject) {
        val folder = payload.optString("folder")
        if (folder.isEmpty()) return
        try {
            val root = scriptsRootDir()
            val target = File(root, folder)
            if (!target.exists()) {
                target.mkdirs()
            }
            sendScriptListSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle CREATE_FOLDER", e)
        }
    }

    private fun handlePushScript(payload: JSONObject) {
        val name = payload.optString("name").ifEmpty { payload.optString("scriptId", "script.js") }
        val content = payload.optString("content", "")
        val runImmediately = payload.optBoolean("runImmediately", false)
        val targetFolderName = payload.optString("targetFolder", "management_pushed")

        if (content.isEmpty()) {
            return
        }
        try {
            val root = scriptsRootDir()
            val targetDir = if (targetFolderName == "." || targetFolderName.isEmpty()) {
                root
            } else {
                File(root, targetFolderName)
            }
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val path = File(targetDir, name).absolutePath
            PFiles.createWithDirs(path)
            PFiles.write(path, content)

            sendScriptListSafe()

            if (runImmediately) {
                runScriptInternal(path)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle PUSH_SCRIPT", e)
        }
    }

    private fun handleRunScript(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return
        runScriptInternal(scriptId)
    }

    private fun runScriptInternal(path: String) {
        try {
            val context = appContext
            val file = ScriptFile(path)
            org.autojs.autojs.model.script.Scripts.run(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to run script: $path", e)
        }
    }

    private fun sendRunningScriptsSafe() {
        try {
            val autoJs = AutoJs.instance
            val executions = autoJs.scriptEngineService.scriptExecutions
            val arr = JSONArray()
            for (execution in executions) {
                val source = execution.source
                val (id, name) = when (source) {
                    is ScriptSource -> source.fullPath to source.name
                    else -> source.toString() to source.toString()
                }
                val obj = JSONObject()
                obj.put("id", id)
                obj.put("name", name)
                arr.put(obj)
            }

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("items", arr)
            send("RUNNING_SCRIPTS", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send running scripts", e)
        }
    }

    private fun sendScheduledScriptsSafe() {
        try {
            val arr = JSONArray()
            val tasks: List<TimedTask> = TimedTaskManager.allTasksAsList

            for (task in tasks) {
                val obj = JSONObject()

                val scriptPath = task.scriptPath
                val id = if (!scriptPath.isNullOrEmpty()) scriptPath else task.id.toString()
                val scheduleId = "timed:${task.id}"
                val name = if (!scriptPath.isNullOrEmpty()) File(scriptPath).name else "定时任务 #${task.id}"

                obj.put("id", id)
                obj.put("name", name)
                obj.put("scheduleId", scheduleId)
                obj.put("type", "timed")

                val cron = buildCronDescription(task)
                if (cron.isNotEmpty()) {
                    obj.put("cron", cron)
                }

                val next = try {
                    task.nextTime
                } catch (e: Exception) {
                    -1L
                }
                if (next > 0) {
                    obj.put("nextRunAt", next)
                }

                arr.put(obj)
            }

            // 广播触发任务（IntentTask）
            val intentTasks = TimedTaskManager.allIntentTasksAsList
            for (task in intentTasks) {
                val obj = JSONObject()

                val scriptPath = task.scriptPath
                val id = if (!scriptPath.isNullOrEmpty()) scriptPath else "intent:${task.id}"
                val scheduleId = "intent:${task.id}"
                val name = if (!scriptPath.isNullOrEmpty()) File(scriptPath).name else "广播任务 #${task.id}"

                obj.put("id", id)
                obj.put("name", name)
                obj.put("scheduleId", scheduleId)
                obj.put("type", "intent")

                val desc = mutableListOf<String>()
                val action = task.action
                if (!action.isNullOrEmpty()) {
                    desc += action
                }
                val category = task.category
                if (!category.isNullOrEmpty()) {
                    desc += "category=$category"
                }
                val dataType = task.dataType
                if (!dataType.isNullOrEmpty()) {
                    desc += "type=$dataType"
                }
                if (desc.isNotEmpty()) {
                    obj.put("cron", desc.joinToString(" · "))
                }

                arr.put(obj)
            }

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("items", arr)
            send("SCHEDULED_SCRIPTS", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send scheduled scripts", e)
        }
    }

    private fun buildCronDescription(task: TimedTask): String {
        return try {
            if (task.isDisposable) {
                val dt = DateTime(task.millis)
                "一次 ${dt.toString("yyyy-MM-dd HH:mm")}" // 一次性任务
            } else if (task.isDaily) {
                val time = LocalTime.fromMillisOfDay(task.millis)
                "每天 ${time.toString("HH:mm")}" // 每天固定时间
            } else {
                val time = LocalTime.fromMillisOfDay(task.millis)
                val timeStr = time.toString("HH:mm")
                val ctx = GlobalAppContext.get()
                val days = mutableListOf<String>()
                if (task.hasDayOfWeek(ctx, DateTimeConstants.MONDAY)) days += "周一"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.TUESDAY)) days += "周二"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.WEDNESDAY)) days += "周三"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.THURSDAY)) days += "周四"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.FRIDAY)) days += "周五"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.SATURDAY)) days += "周六"
                if (task.hasDayOfWeek(ctx, DateTimeConstants.SUNDAY)) days += "周日"

                if (days.isEmpty()) {
                    ""
                } else {
                    "每周${days.joinToString("、")} $timeStr"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build cron description for timed task", e)
            ""
        }
    }

    private fun handleDeleteScheduledTask(payload: JSONObject) {
        val id = payload.optString("id")
        if (id.isEmpty()) return

        try {
            when {
                id.startsWith("timed:") -> {
                    val taskId = id.removePrefix("timed:").toLongOrNull() ?: return
                    val task = TimedTaskManager.getTimedTask(taskId) ?: return
                    TimedTaskManager.removeTaskSync(task)
                }

                id.startsWith("intent:") -> {
                    val taskId = id.removePrefix("intent:").toLongOrNull() ?: return
                    val task = TimedTaskManager.getIntentTask(taskId) ?: return
                    TimedTaskManager.removeTaskSync(task)
                }

                else -> {
                    // 兼容旧格式：可能直接用脚本路径作为 id
                    val tasks = TimedTaskManager.allTasksAsList
                    val match = tasks.firstOrNull { it.scriptPath == id }
                    if (match != null) {
                        TimedTaskManager.removeTaskSync(match)
                    }
                }
            }

            // 删除定时任务后，刷新一次定时任务列表
            sendScheduledScriptsSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle DELETE_SCHEDULED_TASK: $id", e)
        }
    }

    private fun handleInstallApk(payload: JSONObject) {
        val apkName = payload.optString("apkName").trim()
        if (apkName.isEmpty()) {
            Log.w(TAG, "INSTALL_APK missing apkName")
            return
        }

        val mode = payload.optString("mode", "auto")

        scheduler.execute {
            try {
                val address = Pref.getManagementPlatformServerAddress().trim()
                if (address.isEmpty()) {
                    Log.w(TAG, "INSTALL_APK ignored: empty server address in Pref")
                    return@execute
                }

                val base = buildHttpBaseUrl(address).trimEnd('/')
                val encodedName = URLEncoder.encode(apkName, "UTF-8")
                val url = "$base/api/apk/files/$encodedName"

                val request = Request.Builder().url(url).build()
                Log.d(TAG, "Downloading APK from $url")
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "INSTALL_APK download failed: HTTP ${'$'}{response.code}")
                    response.close()
                    return@execute
                }

                val body = response.body
                if (body == null) {
                    Log.w(TAG, "INSTALL_APK download failed: empty body")
                    response.close()
                    return@execute
                }

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    Log.w(TAG, "INSTALL_APK cannot create downloads dir: ${'$'}downloadsDir")
                    response.close()
                    return@execute
                }

                val targetFile = File(downloadsDir, apkName)
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                response.close()

                Log.d(TAG, "APK downloaded to ${'$'}targetFile")

                val wantRoot = when (mode) {
                    "root" -> true
                    "normal" -> false
                    else -> true // auto
                }
                val hasRoot = RootUtils.isRootAvailable()
                val useRoot = wantRoot && hasRoot

                if (useRoot) {
                    val ok = installApkWithRoot(targetFile)
                    if (ok) {
                        Log.d(TAG, "INSTALL_APK installed via root")
                        return@execute
                    }
                    Log.w(TAG, "INSTALL_APK root install failed, fallback to normal installer")
                }

                IntentUtils.installApk(
                    appContext,
                    targetFile.absolutePath,
                    AppFileProvider.AUTHORITY,
                    ToastExceptionHolder(appContext),
                )
                Log.d(TAG, "INSTALL_APK launched system installer for ${'$'}targetFile")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to handle INSTALL_APK", e)
            }
        }
    }

    private fun installApkWithRoot(file: File): Boolean {
        return try {
            val cmd = "pm install -r \"${'$'}{file.absolutePath}\""
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val code = process.waitFor()
            Log.d(TAG, "pm install exit code=$code")
            code == 0
        } catch (e: Throwable) {
            Log.w(TAG, "installApkWithRoot failed", e)
            false
        }
    }

    private fun handleCreateIntentTask(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        val action = payload.optString("action")
        if (scriptId.isEmpty() || action.isEmpty()) return

        try {
            val hasLocal = payload.has("local")
            val localFlag = if (hasLocal) {
                payload.optBoolean("local", false)
            } else {
                action == DynamicBroadcastReceivers.ACTION_STARTUP
            }

            val task = IntentTask().apply {
                scriptPath = scriptId
                this.action = action
                isLocal = localFlag
            }

            TimedTaskManager.addTaskSync(task)

            // 创建广播任务后，刷新一次定时/广播任务列表
            sendScheduledScriptsSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle CREATE_INTENT_TASK for script: $scriptId, action: $action", e)
        }
    }

    private fun handleCreateTimedTask(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        val mode = payload.optString("mode", "once")
        val config = ExecutionConfig.default

        try {
            val task: TimedTask? = when (mode) {
                "once" -> {
                    val ts = payload.optLong("timestamp", -1L)
                    if (ts <= 0L) {
                        Log.w(TAG, "CREATE_TIMED_TASK once mode requires valid timestamp")
                        null
                    } else {
                        val dt = DateTime(ts).toLocalDateTime()
                        TimedTask.disposableTask(dt, scriptId, config)
                    }
                }

                "daily" -> {
                    val timeStr = payload.optString("timeOfDay", "")
                    if (timeStr.isEmpty()) {
                        Log.w(TAG, "CREATE_TIMED_TASK daily mode requires timeOfDay")
                        null
                    } else {
                        val time = LocalTime.parse(timeStr) // 期望 HH:mm
                        TimedTask.dailyTask(time, scriptId, config)
                    }
                }

                "weekly" -> {
                    val timeStr = payload.optString("timeOfDay", "")
                    if (timeStr.isEmpty()) {
                        Log.w(TAG, "CREATE_TIMED_TASK weekly mode requires timeOfDay")
                        null
                    } else {
                        val daysArr = payload.optJSONArray("daysOfWeek")
                        if (daysArr == null || daysArr.length() == 0) {
                            Log.w(TAG, "CREATE_TIMED_TASK weekly mode requires daysOfWeek")
                            null
                        } else {
                            val time = LocalTime.parse(timeStr)
                            var flag = 0L
                            for (i in 0 until daysArr.length()) {
                                val dow = daysArr.optInt(i, -1)
                                if (dow in 1..7) {
                                    flag = flag or TimedTask.getDayOfWeekTimeFlag(dow)
                                }
                            }
                            if (flag == 0L) {
                                Log.w(TAG, "CREATE_TIMED_TASK weekly mode produced empty timeFlag")
                                null
                            } else {
                                TimedTask.weeklyTask(time, flag, scriptId, config)
                            }
                        }
                    }
                }

                else -> {
                    Log.w(TAG, "Unsupported CREATE_TIMED_TASK mode: $mode")
                    null
                }
            }

            if (task == null) {
                return
            }

            TimedTaskManager.addTaskSync(task)

            // 创建定时任务后，刷新一次定时任务列表
            sendScheduledScriptsSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle CREATE_TIMED_TASK for script: $scriptId", e)
        }
    }

    private fun handleRequestLogTail(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        val requestedLines = payload.optInt("lines", DEFAULT_LOG_TAIL_LINES).let { hint ->
            if (hint <= 0) DEFAULT_LOG_TAIL_LINES else hint
        }

        try {
            val console = AutoJs.instance.globalConsole
            val allLines: List<String> = synchronized(console.logEntries) {
                console.logEntries.map { it.content.toString() }
            }

            val tailLines: List<String> = if (allLines.isEmpty()) {
                emptyList()
            } else if (scriptId == GLOBAL_LOG_ID) {
                // 全局总日志：直接从结尾截取
                allLines.takeLast(requestedLines.coerceAtMost(allLines.size))
            } else {
                val range = synchronized(scriptLogRanges) { scriptLogRanges[scriptId] }
                if (range == null) {
                    // 没有记录范围时，退化为全局日志尾部
                    allLines.takeLast(requestedLines.coerceAtMost(allLines.size))
                } else {
                    val start = range.startIndex.coerceIn(0, allLines.size)
                    val endExclusive = (range.endIndex ?: allLines.size).coerceIn(start, allLines.size)
                    val slice = allLines.subList(start, endExclusive)
                    if (slice.size <= requestedLines) slice else slice.takeLast(requestedLines)
                }
            }

            val arr = JSONArray()
            tailLines.forEach { arr.put(it) }

            val reply = JSONObject()
            reply.put("deviceId", deviceId())
            reply.put("scriptId", scriptId)
            reply.put("lines", arr)
            send("LOG_LINES", reply)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build log tail for $scriptId", e)
            val reply = JSONObject()
            reply.put("deviceId", deviceId())
            reply.put("scriptId", scriptId)
            reply.put("lines", JSONArray())
            send("LOG_LINES", reply)
        }
    }

    private fun handleRequestScriptContent(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        try {
            val file = File(scriptId)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "Script file not found: $scriptId")
                return
            }
            val content = PFiles.read(file)
            val reply = JSONObject()
            reply.put("deviceId", deviceId())
            reply.put("scriptId", scriptId)
            reply.put("content", content)
            send("SCRIPT_CONTENT", reply)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read script content: $scriptId", e)
        }
    }

    private fun handleUpdateScriptContent(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return
        if (!payload.has("content")) return

        val content = payload.optString("content", null) ?: return

        try {
            PFiles.createWithDirs(scriptId)
            PFiles.write(scriptId, content)
            // 更新本地脚本后，刷新脚本列表（大小与更新时间会变化）
            sendScriptListSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update script content: $scriptId", e)
        }
    }

    private fun handleDeleteScript(payload: JSONObject) {
        val scriptId = payload.optString("scriptId")
        if (scriptId.isEmpty()) return

        try {
            val file = File(scriptId)
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "Script file not found for delete: $scriptId")
                return
            }

            val root = scriptsRootDir()
            val filePath = file.canonicalPath
            val rootPath = root.canonicalPath
            if (!filePath.startsWith(rootPath)) {
                Log.w(TAG, "Refusing to delete script outside root: $scriptId")
                return
            }

            val deleted = file.delete()
            if (!deleted) {
                Log.w(TAG, "Failed to delete script file: $scriptId")
                return
            }

            sendScriptListSafe()
        } catch (e: Exception) {
            Log.w(TAG, "Exception while deleting script: $scriptId", e)
        }
    }

    private fun handleRequestScreenshot(payload: JSONObject) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "REQUEST_SCREENSHOT ignored: requires Android 11 (API 30)+")
            return
        }

        val service = AutoJsAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "REQUEST_SCREENSHOT ignored: accessibility service not running")
            return
        }

        val quality = payload.optInt("quality", 75).coerceIn(30, 100)
        val maxWidth = payload.optInt("maxWidth", 0).coerceAtLeast(0)
        val maxHeight = payload.optInt("maxHeight", 0).coerceAtLeast(0)

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AndroidAccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AndroidAccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (hardwareBitmap == null) {
                                Log.w(TAG, "Screenshot hardwareBitmap is null")
                                return
                            }
                            val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            hardwareBitmap.recycle()
                            sendScreenshotBitmap(bitmap, quality, maxWidth, maxHeight)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to handle screenshot success", e)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed, errorCode=$errorCode")
                    }
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to request screenshot", e)
        }
    }

    private fun sendScreenshotBitmap(
        bitmap: Bitmap,
        quality: Int,
        maxWidth: Int,
        maxHeight: Int,
    ) {
        try {
            val screenWidth = bitmap.width
            val screenHeight = bitmap.height
            val targetBitmap = if (maxWidth > 0 || maxHeight > 0) {
                val width = bitmap.width
                val height = bitmap.height
                var scale = 1.0f
                if (maxWidth > 0) {
                    val sx = maxWidth.toFloat() / width.toFloat()
                    if (sx < scale) {
                        scale = sx
                    }
                }
                if (maxHeight > 0) {
                    val sy = maxHeight.toFloat() / height.toFloat()
                    if (sy < scale) {
                        scale = sy
                    }
                }
                if (scale < 1f) {
                    val newWidth = (width * scale).toInt().coerceAtLeast(1)
                    val newHeight = (height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                } else {
                    bitmap
                }
            } else {
                bitmap
            }

            lastScreenWidth = screenWidth
            lastScreenHeight = screenHeight
            lastScreenshotImageWidth = targetBitmap.width
            lastScreenshotImageHeight = targetBitmap.height

            val output = ByteArrayOutputStream()
            val q = quality.coerceIn(30, 100)
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, q, output)
            val bytes = output.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("contentType", "image/jpeg")
            payload.put("data", base64)
            payload.put("width", targetBitmap.width)
            payload.put("height", targetBitmap.height)
            payload.put("timestamp", System.currentTimeMillis())

            send("SCREENSHOT", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send screenshot", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun handleTouchEvent(payload: JSONObject) {
        val type = payload.optString("type")
        if (type.isNullOrEmpty()) {
            Log.w(TAG, "TOUCH_EVENT missing type")
            return
        }

        val startObj = payload.optJSONObject("start")
        val rawStartX = startObj?.optInt("x", -1) ?: -1
        val rawStartY = startObj?.optInt("y", -1) ?: -1
        if (rawStartX < 0 || rawStartY < 0) {
            Log.w(TAG, "TOUCH_EVENT missing valid start coordinates")
            return
        }

        val endObj = payload.optJSONObject("end")
        val rawEndX = endObj?.optInt("x", -1) ?: -1
        val rawEndY = endObj?.optInt("y", -1) ?: -1
        val durationMs = payload.optLong("durationMs", 0L)

        val (startX, startY) = mapTouchCoordinate(rawStartX, rawStartY)
        val (endX, endY) = if (rawEndX >= 0 && rawEndY >= 0) {
            mapTouchCoordinate(rawEndX, rawEndY)
        } else {
            startX to startY
        }

        performTouchGesture(
            type,
            startX,
            startY,
            endX,
            endY,
            durationMs,
        )
    }

    private fun mapTouchCoordinate(x: Int, y: Int): Pair<Int, Int> {
        val imageW = lastScreenshotImageWidth
        val imageH = lastScreenshotImageHeight
        val screenW = lastScreenWidth
        val screenH = lastScreenHeight
        if (imageW > 0 && imageH > 0 && screenW > 0 && screenH > 0) {
            val scaleX = screenW.toFloat() / imageW.toFloat()
            val scaleY = screenH.toFloat() / imageH.toFloat()
            val mappedX = (x * scaleX).roundToInt().coerceIn(0, screenW - 1)
            val mappedY = (y * scaleY).roundToInt().coerceIn(0, screenH - 1)
            return mappedX to mappedY
        }
        return x to y
    }

    private fun performTouchGesture(
        type: String,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ) {
        val service = AutoJsAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "TOUCH_EVENT ignored: accessibility service not running")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "TOUCH_EVENT ignored: requires Android 7.0 (API 24)+")
            return
        }

        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            if (type == "swipe" && (startX != endX || startY != endY)) {
                lineTo(endX.toFloat(), endY.toFloat())
            }
        }

        val duration = when (type) {
            "long_press" -> durationMs.takeIf { it > 0 } ?: 700L
            "swipe" -> durationMs.takeIf { it > 0 } ?: 300L
            else -> durationMs.takeIf { it > 0 } ?: 120L
        }.coerceAtLeast(80L)

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(description, null, null)
        Log.d(
            TAG,
            "dispatchGesture(type=$type, start=($startX,$startY), end=($endX,$endY), duration=$duration) -> $ok",
        )
    }

    private fun handleDeviceAction(payload: JSONObject) {
        val action = payload.optString("action")
        if (action.isNullOrEmpty()) {
            Log.w(TAG, "DEVICE_ACTION missing action field")
            return
        }

        try {
            when (action) {
                "back", "home" -> {
                    val bridge = AutoJsAccessibilityService.bridge
                    if (bridge == null) {
                        Log.w(TAG, "DEVICE_ACTION $action ignored: accessibility bridge is null")
                        return
                    }

                    val automator = GlobalActionAutomator(
                        appContext,
                        null,
                    ) {
                        bridge.ensureServiceStarted()
                        bridge.service ?: throw IllegalStateException("AccessibilityService not running")
                    }

                    if (action == "back") {
                        automator.back()
                    } else {
                        automator.home()
                    }
                }

                "volume_up" -> {
                    val device = Device(appContext)
                    val current = device.musicVolume
                    val max = device.musicMaxVolume
                    if (current < max) {
                        device.setMusicVolume(current + 1)
                    }
                }

                "volume_down" -> {
                    val device = Device(appContext)
                    val current = device.musicVolume
                    if (current > 0) {
                        device.setMusicVolume(current - 1)
                    }
                }

                "mute" -> {
                    val device = Device(appContext)
                    device.setMusicVolume(0)
                }

                else -> Log.w(TAG, "Unknown DEVICE_ACTION: $action")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to handle DEVICE_ACTION: $action", e)
        }
    }

    fun onScriptStart(execution: ScriptExecution) {
        recordLogStart(execution)
        sendScriptStatus(execution, "running", null)
    }

    fun onScriptSuccess(execution: ScriptExecution) {
        recordLogEnd(execution)
        sendScriptStatus(execution, "success", null)
    }

    fun onScriptException(execution: ScriptExecution, e: Throwable) {
        recordLogEnd(execution)
        sendScriptStatus(execution, "error", e.message)
    }

    private fun sendScriptStatus(execution: ScriptExecution, status: String, detail: String?) {
        try {
            val scriptId = scriptIdFromExecution(execution)
            val payload = JSONObject()
            payload.put("deviceId", deviceId())
            payload.put("scriptId", scriptId)
            payload.put("status", status)
            if (!detail.isNullOrEmpty()) {
                payload.put("detail", detail)
            }
            send("SCRIPT_STATUS_UPDATE", payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send script status", e)
        }
    }

    private fun scriptIdFromExecution(execution: ScriptExecution): String {
        val source = execution.source
        return when (source) {
            is ScriptSource -> source.fullPath
            else -> source.toString()
        }
    }

    private fun recordLogStart(execution: ScriptExecution) {
        try {
            val scriptId = scriptIdFromExecution(execution)
            val console = AutoJs.instance.globalConsole
            val startIdx = synchronized(console.logEntries) { console.logEntries.size }
            synchronized(scriptLogRanges) {
                scriptLogRanges[scriptId] = ScriptLogRange(startIdx, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record log start", e)
        }
    }

    private fun recordLogEnd(execution: ScriptExecution) {
        try {
            val scriptId = scriptIdFromExecution(execution)
            val console = AutoJs.instance.globalConsole
            val endIdx = synchronized(console.logEntries) { console.logEntries.size }
            synchronized(scriptLogRanges) {
                val range = scriptLogRanges[scriptId]
                if (range == null) {
                    scriptLogRanges[scriptId] = ScriptLogRange(0, endIdx)
                } else {
                    range.endIndex = endIdx
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record log end", e)
        }
    }

    private fun deviceId(): String {
        val device = Device(appContext)
        val androidId = device.androidId
        if (!androidId.isNullOrEmpty()) {
            return androidId
        }
        val serial = Device.serial
        if (!serial.isNullOrEmpty()) {
            return serial
        }
        return Build.MODEL ?: "unknown"
    }
}
