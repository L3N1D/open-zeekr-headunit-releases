#!/usr/bin/env python3
"""Exercise production socket I/O and extracted NativeAA wait/catch blocks on the JVM.
No Android device or radios; framework services are doubles. Raw reports are never inputs.
"""
from pathlib import Path
import os, re, subprocess
ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / 'build/host-bugsink'
OUT.mkdir(parents=True, exist_ok=True)
base = ROOT / 'app/src/main/java/com/lenid/zoh'
native = base / 'connection/wifi/modes/nativeaa'
for folder, names in [(base/'connection/projection', ['SocketProjectionConnection.kt', 'ProjectionConnection.kt', 'LoopbackBindPolicy.kt']), (native, ['NativeCredentialsPolicy.kt', 'NativeStrategy.kt', 'SoftApBssidPolicy.kt'])]:
    for name in names:
        (OUT/name).write_text((folder/name).read_text())
transport = ROOT / 'carplay/src/main/java/com/shilapi/xcertplay/transport/TransportIo.kt'
(OUT/'TransportIo.kt').write_bytes(transport.read_bytes())
(OUT/'TransportModules.kt').write_text("""package com.lenid.zoh.modules
import com.shilapi.xcertplay.transport.TransportIo
object TransportModules { val androidAuto = TransportIo.DEFAULT }
""")
s = (native/'NativeAaHandshakeManager.kt').read_text()
wait = s[s.index('            val credentialsDeadline ='):s.index('            // Read once.', s.index('            val credentialsDeadline ='))]
catch = s[s.index('        } catch (e: CancellationException) {', s.index('    private suspend fun handleHandshake(')):s.index('        } finally {', s.index('            AppLog.e("NativeAA: Handshake error:'))]
(OUT/'MacAddressPolicy.kt').write_text((base/'connection/wifi/MacAddressPolicy.kt').read_text())
(OUT/'WaitFixture.kt').write_text('''package probe
import com.lenid.zoh.connection.wifi.modes.nativeaa.*
import com.lenid.zoh.utils.AppLog
import android.os.SystemClock
import kotlinx.coroutines.*
class WaitFixture {
 data class Credentials(val bssid:String)
 var credentials:Credentials?=Credentials("02:00:00:00:00:00")
 var abortedLocally=false
 var isRunning=true
 val transport=NativeStrategy.WIFI_DIRECT
 val session=WppHandshakeSession()
 val launcher=Launcher()
 class Launcher { var refreshes=0; fun triggerWifiDirectRefresh(){refreshes++} }
 var onTick: suspend WaitFixture.()->Unit = {}
 suspend fun tick(ms:Long){ SystemClock.now+=ms; onTick() }
 val CREDENTIALS_WAIT_MS=60_000L
 suspend fun waitForReadback() = withContext(Dispatchers.Unconfined) {
'''+wait+'''
 }
}
class CancellationFixture {
 var abortedLocally=false
 suspend fun run(fail:Boolean=false) {
  try { if(fail) throw java.io.IOException("peer failure") else awaitCancellation()
'''+catch+'''
  }
 }
}
''')
# Minimal Android interfaces used by the complete production socket class. No networking is mocked.
(OUT/'Context.kt').write_text('''package android.content
open class Context { fun getSystemService(name:String):Any = error("framework path not expected")
 companion object { const val CONNECTIVITY_SERVICE="connectivity" } }
''')
(OUT/'Build.kt').write_text('''package android.os
object Build { object VERSION { const val SDK_INT=36 }; object VERSION_CODES { const val M=23 } }
object SystemClock { var now=0L; fun elapsedRealtime()=now }
''')
(OUT/'Network.kt').write_text('''package android.net
class Network { fun bindSocket(s:java.net.Socket){} }
class NetworkCapabilities { fun hasTransport(t:Int)=false; companion object { const val TRANSPORT_WIFI=1; const val TRANSPORT_CELLULAR=0 } }
class NetworkInfo { val isConnected=false }
class NetworkRequest { class Builder { fun addTransportType(t:Int)=this; fun build()=NetworkRequest() } }
class ConnectivityManager {
 val allNetworks=emptyArray<Network>(); val activeNetwork:Network?=null
 fun getNetworkCapabilities(n:Network):NetworkCapabilities?=null
 open class NetworkCallback { open fun onAvailable(network:Network){} }
 fun registerNetworkCallback(r:NetworkRequest,c:NetworkCallback){}; fun unregisterNetworkCallback(c:NetworkCallback){}
 fun getNetworkInfo(t:Int):NetworkInfo?=null
 companion object { const val TYPE_WIFI=1 }
}
''')
(OUT/'AppLog.kt').write_text('''package com.lenid.zoh.utils
object AppLog {
 val errors=java.util.concurrent.CopyOnWriteArrayList<String>()
 fun e(s:String,t:Throwable?=null){errors.add(s)}; fun e(t:Throwable){errors.add(t.toString())}
 fun d(s:String){}; fun i(s:String,vararg args:Any?){}; fun w(s:String,t:Throwable?=null){}
}
''')
# Real state machine keeps terminal and holding behavior faithful to production.
for name in ['WppHandshakeSession.kt', 'NativeHandoffPolicy.kt', 'WppStatus.kt']:
    (OUT/name).write_text((native/name).read_text())
cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
def jar(group,name,version='*'):
    return next(p for p in (cache/group/name).glob(version+'/*/*.jar') if p.name==name+'-'+p.parent.parent.name+'.jar')
kotlin=re.search(r'kotlin-gradle-plugin:([0-9.]+)',(ROOT/'build.gradle.kts').read_text())[1]
runtime=[jar('org.jetbrains.kotlin','kotlin-stdlib',kotlin),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),jar('org.jetbrains','annotations','13.0')]
compiler=runtime+[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable',kotlin),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlin','kotlin-script-runtime',kotlin)]
cp=os.pathsep.join(map(str,runtime))
subprocess.run(['java','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-classpath',cp,'-d',str(OUT/'probe.jar'),*map(str,OUT.glob('*.kt')),str(Path(__file__).with_name('Regression.kt'))],check=True,timeout=180)
subprocess.run(['java','-cp',str(OUT/'probe.jar')+os.pathsep+cp,'probe.RegressionKt'],check=True,timeout=35)
