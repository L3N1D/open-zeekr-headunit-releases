package probe

import android.content.Context
import android.os.SystemClock
import com.lenid.zoh.connection.projection.SocketProjectionConnection
import com.lenid.zoh.connection.wifi.modes.nativeaa.*
import com.lenid.zoh.utils.AppLog
import kotlinx.coroutines.*
import java.net.*
import java.io.*
import java.util.concurrent.*
import kotlin.concurrent.thread

private class ObservedSocket(private val delegate:Socket):Socket() {
    val reading=CountDownLatch(1)
    override fun getInetAddress()=delegate.inetAddress
    override fun getPort()=delegate.port
    override fun isConnected()=delegate.isConnected
    override fun isClosed()=delegate.isClosed
    override fun close()=delegate.close()
    override fun setSoTimeout(timeout:Int){delegate.soTimeout=timeout}
    override fun getOutputStream()=delegate.getOutputStream()
    override fun getInputStream()=object:FilterInputStream(delegate.getInputStream()) {
        override fun read(b:ByteArray,off:Int,len:Int):Int { reading.countDown(); return super.read(b,off,len) }
    }
}

private fun localClose() {
    ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { server ->
        Socket(server.inetAddress,server.localPort).use { peer ->
            val socket=ObservedSocket(server.accept())
            val conn=SocketProjectionConnection(socket,Context())
            check(conn.isConnected)
            var result=0
            val worker=thread {result=conn.recvBlocking(ByteArray(4),4,5000,true)}
            check(socket.reading.await(2,TimeUnit.SECONDS))
            conn.disconnect()
            worker.join(2000)
            check(!worker.isAlive && result == -1)
            check(!conn.isConnected)
            check(AppLog.errors.isEmpty()) { "Local close reported as failure" }
            conn.disconnect()
        }
    }
}

private fun remoteReset() {
    ServerSocket(0,1,InetAddress.getLoopbackAddress()).use { server ->
        val peer=Socket(server.inetAddress,server.localPort)
        val socket=ObservedSocket(server.accept())
        val conn=SocketProjectionConnection(socket,Context())
        var result=0
        val worker=thread {result=conn.recvBlocking(ByteArray(4),4,5000,true)}
        check(socket.reading.await(2,TimeUnit.SECONDS))
        peer.setSoLinger(true,0); peer.close()
        worker.join(2000)
        check(!worker.isAlive && result == -1)
        check(!conn.isConnected)
        check(AppLog.errors.any {it.contains("Socket broken")}) { "Remote reset hidden" }
        conn.disconnect()
    }
}

fun main()=runBlocking {
    repeat(20){AppLog.errors.clear(); localClose()}
    remoteReset()
    println("PASS: 20 loopback local-close races and genuine peer reset")
    AppLog.errors.clear()
    val cancelled=CancellationFixture()
    val job=launch(start=CoroutineStart.UNDISPATCHED) {cancelled.run()}
    job.cancelAndJoin()
    check(job.isCancelled && cancelled.abortedLocally && AppLog.errors.isEmpty())
    CancellationFixture().run(fail=true)
    check(AppLog.errors.single().contains("Handshake error"))
    println("PASS: cancellation propagates; genuine handshake failure remains an error")
    SystemClock.now=0
    val arrives=WaitFixture()
    arrives.onTick={if(SystemClock.now>=500) credentials=WaitFixture.Credentials("02:1a:2b:3c:4d:5e")}
    arrives.waitForReadback()
    check(SystemClock.now==500L && NativeCredentialsPolicy.isUsableBssid(arrives.credentials?.bssid))
    SystemClock.now=0
    val unavailable=WaitFixture(); unavailable.waitForReadback()
    check(SystemClock.now==60_000L)
    check(!NativeCredentialsPolicy.isUsableBssid(unavailable.credentials?.bssid))
    check(NativeCredentialsPolicy.onUnusableBssid(NativeStrategy.WIFI_DIRECT)==UnusableBssidAction.ABORT)
    SystemClock.now=0
    val failed=WaitFixture(); failed.onTick={session.on(WppEvent.CredentialsUnavailable)}
    failed.waitForReadback(); check(SystemClock.now==500L)
    SystemClock.now=0
    val stopped=WaitFixture(); stopped.onTick={isRunning=false}
    stopped.waitForReadback(); check(SystemClock.now==500L && stopped.abortedLocally)
    val ready=WaitFixture(); ready.credentials=WaitFixture.Credentials("02:1a:2b:3c:4d:5e")
    SystemClock.now=0; ready.waitForReadback(); check(SystemClock.now==0L)
    println("PASS: late readback, missing BSSID deadline, terminal/stop guards, ready credentials")
}
