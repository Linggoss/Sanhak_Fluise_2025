package com.example.myapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

private const val BASE_URL = "http://10.0.2.2:8000"

// ---------------------- Retrofit API 정의 ---------------------- //

interface ServerApi {
    @POST("/register_apps")
    suspend fun registerApps(@Body body: RegisterAppsReq): retrofit2.Response<Unit>

    @GET("/next_cmds/{device_id}")
    suspend fun getNextCmds(@Path("device_id") deviceId: String): NextCmdsResp
}

@JsonClass(generateAdapter = true)
data class RegisterAppsReq(
    val device_id: String,
    val accessible_apps: Map<String, String>,
    val inaccessible_apps: Map<String, String>
)

@JsonClass(generateAdapter = true)
data class CommandDto(val type: String, val pkg: String?)

@JsonClass(generateAdapter = true)
data class NextCmdsResp(val commands: List<CommandDto>)


// ====================== MainActivity ====================== //

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQ_CODE_FOREGROUND_DATA_SYNC = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 14(API 34)+: dataSync 권한 체크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startAgentService()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC),
                    REQ_CODE_FOREGROUND_DATA_SYNC
                )
            }
        } else {
            startAgentService()
        }

        setContent {
            MaterialTheme {
                val vm = ViewModelProvider(
                    this,
                    ChatViewModel.Factory(applicationContext)
                )[ChatViewModel::class.java]
                ChatScreen(vm)
            }
        }
    }

    private fun startAgentService() {
        Intent(this, CommandService::class.java).also { svc ->
            ContextCompat.startForegroundService(this, svc)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_FOREGROUND_DATA_SYNC
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAgentService()
        } else {
            Toast.makeText(
                this,
                "데이터 동기화 포그라운드 서비스 권한이 필요합니다.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}


// ====================== ChatViewModel & UI ====================== //

class ChatViewModel private constructor(private val ctx: Context) : ViewModel() {

    private val _messages = MutableStateFlow<List<Turn>>(emptyList())
    val messages: StateFlow<List<Turn>> = _messages.asStateFlow()

    private val okClient = OkHttpClient.Builder().build()
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
    private val api = retrofit.create(ServerApi::class.java)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val deviceId: String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                registerInstalledApps()
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "registerInstalledApps 실패", e)
            }
        }
    }

    fun sendUserMessage(text: String) {
        if (text.isBlank()) return
        appendTurn("user", text)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                streamChat(text)
                api.getNextCmds(deviceId).commands.forEach { handleCommand(it) }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "sendUserMessage 오류", e)
                appendTurn("system", "⚠️ 오류 발생: ${e.localizedMessage}")
            }
        }
    }

    private fun streamChat(userText: String) {
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("message", userText)
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url("$BASE_URL/chat")
            .post(json)
            .build()

        okClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("$resp")
            val src = resp.body?.source() ?: return
            while (!src.exhausted()) {
                src.readUtf8Line()?.takeIf { it.startsWith("data:") }?.let {
                    val data = it.removePrefix("data:").trim()
                    if (data.isNotEmpty()) appendTurn("assistant", data)
                }
            }
        }
    }

    private fun handleCommand(cmd: CommandDto) {
        if (cmd.type == "open_app" && cmd.pkg != null) {
            viewModelScope.launch {
                // main 스레드에서 호출하도록 위임
                withContext(Dispatchers.Main) {
                    launchApp(cmd.pkg)
                }
            }
        }
    }

    private fun launchApp(packageName: String) {
        val pm = ctx.packageManager
        pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(this)
        } ?: appendTurn("system", "앱 실행 실패: $packageName")
    }

    private suspend fun registerInstalledApps() {
        try {
            val pm = ctx.packageManager
            val accessible = mutableMapOf<String, String>()
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(mainIntent, 0).forEach { ri ->
                accessible[ri.loadLabel(pm).toString()] = ri.activityInfo.packageName
            }

            val body = RegisterAppsReq(
                device_id = deviceId,
                accessible_apps = accessible,
                inaccessible_apps = emptyMap()
            )
            api.registerApps(body)
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "uploadAppList 실패", e)
        }
    }

    private fun appendTurn(role: String, text: String) {
        _messages.update { it + Turn(role, text) }
    }

    object Factory : ViewModelProvider.Factory {
        private lateinit var context: Context
        operator fun invoke(ctx: Context): Factory { context = ctx; return this }
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(context) as T
        }
    }
}

@JsonClass(generateAdapter = true)
data class Turn(val role: String, val text: String)

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val messages by vm.messages.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF0F0F0))
    ) {
        // 대화창: 화면 상단부터 채우기
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(12.dp)
        ) {
            items(messages) { msg ->
                Text(
                    text = "${msg.role}: ${msg.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }

        Divider()

        // 입력창: 화면 하단 고정
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .navigationBarsPadding()
                .imePadding()
                .padding(8.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("메시지 입력") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                vm.sendUserMessage(input)
                input = ""
            }) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}