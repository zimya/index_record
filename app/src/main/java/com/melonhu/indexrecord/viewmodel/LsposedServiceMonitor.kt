package com.melonhu.indexrecord.viewmodel

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 进程级 LSPosed 服务状态持有者。
 * XposedServiceHelper.registerListener 仅支持注册一次且会清空内部缓存，
 * 因此必须在 Application 生命周期内单例持有，避免 Activity/ViewModel 重建后丢失绑定。
 */
object LsposedServiceMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    @Volatile
    private var boundService: XposedService? = null

    private val _status = MutableStateFlow<LsposedStatus>(LsposedStatus.Checking)
    val status: StateFlow<LsposedStatus> = _status.asStateFlow()

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }

        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    boundService = service
                    _status.value = readLsposedStatus(service)
                }

                override fun onServiceDied(service: XposedService) {
                    if (boundService === service) {
                        boundService = null
                        _status.value = LsposedStatus.Inactive
                    }
                }
            })
        } catch (_: Throwable) {
            _status.value = LsposedStatus.Inactive
        }

        scope.launch {
            delay(1500)
            if (_status.value is LsposedStatus.Checking) {
                _status.value = LsposedStatus.Inactive
            }
        }
    }

    suspend fun refresh() {
        val service = boundService
        if (service != null) {
            _status.value = readLsposedStatus(service)
        } else {
            delay(300)
        }
    }

    private fun readLsposedStatus(service: XposedService): LsposedStatus {
        return try {
            val scopes = try {
                service.scope?.toList() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
            val frameworkName = try {
                service.frameworkName ?: "LSPosed"
            } catch (_: Throwable) {
                "LSPosed"
            }
            val frameworkVersion = try {
                service.frameworkVersion
            } catch (_: Throwable) {
                null
            }
            LsposedStatus.Active(
                frameworkName = frameworkName,
                frameworkVersion = frameworkVersion,
                activeScopes = scopes
            )
        } catch (_: Throwable) {
            boundService = null
            LsposedStatus.Inactive
        }
    }
}
