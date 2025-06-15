/**
 * Copyright (C) 2018-2022 Jason C.H
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package io.xdea.flutter_vpn

import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.NonNull
import android.util.Log

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import org.strongswan.android.logic.VpnStateService

class FlutterVpnPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var activityBinding: ActivityPluginBinding

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel

    companion object {
        private const val TAG = "FlutterVpnPlugin"
    }

    private var vpnStateService: VpnStateService? = null
    private val vpnStateServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            vpnStateService = (service as VpnStateService.LocalBinder).service
            VpnStateHandler.vpnStateService = vpnStateService
            vpnStateService?.registerListener(VpnStateHandler)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnStateService = null
            VpnStateHandler.vpnStateService = null
        }
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        // Load charon bridge
        System.loadLibrary("androidbridge")

        // Register method channel.
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_vpn")
        channel.setMethodCallHandler(this)

        // Register event channel to handle state change.
        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "flutter_vpn_states")
        eventChannel.setStreamHandler(VpnStateHandler)

        flutterPluginBinding.applicationContext.bindService(
            Intent(flutterPluginBinding.applicationContext, VpnStateService::class.java),
            vpnStateServiceConnection,
            Service.BIND_AUTO_CREATE
        )
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivity() {
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "prepare" -> {
                val intent = VpnService.prepare(activityBinding.activity.applicationContext)
                if (intent != null) {
                    var listener: PluginRegistry.ActivityResultListener? = null
                    listener = PluginRegistry.ActivityResultListener { req, res, _ ->
                        result.success(req == 0 && res == RESULT_OK)
                        listener?.let { activityBinding.removeActivityResultListener(it) }
                        true
                    }
                    activityBinding.addActivityResultListener(listener)
                    activityBinding.activity.startActivityForResult(intent, 0)
                } else {
                    // Already prepared if intent is null.
                    result.success(true)
                }
            }
            "prepared" -> {
                val intent = VpnService.prepare(activityBinding.activity.applicationContext)
                result.success(intent == null)
            }
            "connect" -> {
                Log.d(TAG, "connect() called")
                val prep = VpnService.prepare(activityBinding.activity.applicationContext)
                if (prep != null) {
                    Log.d(TAG, "VPN not prepared yet, showing permission dialog")
                    result.success(false)
                    return
                }
                Log.d(TAG, "VPN permission granted")
                val args = call.arguments as Map<*, *>
                Log.d(TAG, "Arguments: $args")
                val useP12 = args.containsKey("P12Password")
                Log.d(TAG, "Using P12: $useP12")
                val profileInfo = Bundle().apply {
                    putString("Name", args["Name"] as String).also { Log.d(TAG, "Name: ${args["Name"]}") }
                    putString("Server", args["Server"] as String).also { Log.d(TAG, "Server: ${args["Server"]}") }
                    (args["MTU"] as? Int)?.let { putInt("MTU", it).also { Log.d(TAG, "MTU: $it") } }
                    (args["Port"] as? Int)?.let { putInt("Port", it).also { Log.d(TAG, "Port: $it") } }
                    if (useP12) {
                        putString("VpnType", "ikev2-p12").also { Log.d(TAG, "VpnType: ikev2-p12") }
                        putString("P12Password", args["P12Password"] as String).also { Log.d(TAG, "P12Password provided") }
                    } else {
                        putString("VpnType", "ikev2-eap").also { Log.d(TAG, "VpnType: ikev2-eap") }
                        putString("Username", args["Username"] as String).also { Log.d(TAG, "Username: ${args["Username"]}") }
                        putString("Password", args["Password"] as String).also { Log.d(TAG, "Password provided") }
                    }
                }
                Log.d(TAG, "ProfileInfo Bundle: $profileInfo")
                vpnStateService?.connect(profileInfo, true)
                Log.d(TAG, "connect() invoked on VpnStateService")
                result.success(true)
            }
            "getCurrentState" -> {
                if (vpnStateService?.errorState != VpnStateService.ErrorState.NO_ERROR)
                    result.success(4)
                else
                    result.success(vpnStateService?.state?.ordinal)
            }
            "getCharonErrorState" -> result.success(vpnStateService?.errorState?.ordinal)
            "disconnect" -> vpnStateService?.disconnect()
            else -> result.notImplemented()
        }
    }
}
