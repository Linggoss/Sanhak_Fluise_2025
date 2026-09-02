/*  ───────────────────  AgentClient.kt  ───────────────────  */
package com.example.myapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.IOException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AgentClient {
    /* ─── 서버 주소 ─── */
    private const val SERVER = "http://10.0.2.2:8000"   // ← 필요하면 교체

    /* ─── OkHttp (타임아웃 30초, 로그 인터셉터 선택) ─── */
    private val ok: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /* ───────────────── 앱 목록 업로드 ───────────────── */
    suspend fun uploadAppList(ctx: Context, deviceId: String) = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        // 홈 런처에 노출되는 Activity(아이콘) → “사용자 실행 가능” 패키지 집합
        val launcherPkgs = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.toSet()

        val accessible = JSONObject()   // 실행 가능
        val inaccessible = JSONObject()   // 실행 불가

        // 기기 내 모든 앱 순회
        pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { ai ->
            val label = pm.getApplicationLabel(ai).toString()   // 사람이 읽는 앱 이름
            val pkg = ai.packageName                            // 패키지명
            val isLaunchable = pkg in launcherPkgs || pkg in WHITELIST_HIDDEN
            // 필터 조건: 런처 아이콘 있거나 화이트리스트 포함 ⇒ 실행 가능
            if (isLaunchable) accessible.put(label, pkg) else inaccessible.put(label, pkg)
        }

        //서버에 보낼 구성
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("accessible_apps", accessible)
            .put("inaccessible_apps", inaccessible)
            .toString()
            .toRequestBody("application/json".toMediaType())

        try {
            ok.newCall(
                Request.Builder().url("$SERVER/register_apps").post(body).build()
            ).execute().close()
        } catch (e: IOException) {
            // 서버가 꺼져 있어도 앱이 죽지 않도록 예외만 로깅
            android.util.Log.e("AgentClient", "🚫 uploadAppList 실패: ${e.message}")
        }
    }

    /* ─────────────── 명령 폴링 + 실행 ─────────────── */
    suspend fun pollAndExecute(ctx: Context, deviceId: String) = withContext(Dispatchers.IO) {
        val res = try {
            ok.newCall(Request.Builder().url("$SERVER/next_cmds/$deviceId").build()).execute()
        } catch (e: IOException) {
            android.util.Log.e("AgentClient", "🚫 poll 실패: ${e.message}")
            return@withContext
        }

        res.use {
            val arr = JSONObject(it.body!!.string()).optJSONArray("commands") ?: return@withContext
            repeat(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                if (c.optString("type") == "open_app") openApp(ctx, c.optString("pkg"))
            }
        }
    }

    /* ─────────────── 앱 실행 ─────────────── */
    private fun openApp(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.run {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(this)
        } ?: android.util.Log.e("AgentClient", "실행 불가 패키지: $pkg")
    }

    private val WHITELIST_HIDDEN = emptySet<String>()
}