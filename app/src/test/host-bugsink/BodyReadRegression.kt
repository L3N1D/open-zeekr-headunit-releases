package com.lenid.zoh.aap

import android.content.Context
import com.lenid.zoh.connection.projection.SocketProjectionConnection
import com.lenid.zoh.utils.AppLog
import java.io.*
import java.net.*

/** Synthetic byte timing, real release reader/socket/module-6 transfer implementation. */
fun bodyReadRegression() {
    for (prefix in listOf(0, 300, 4565)) {
        val input=BodyInput(prefix, timeout=true)
        val connection=SocketProjectionConnection(BodySocket(input),Context())
        val handler=AapMessageHandler()
        AapMessageIncoming.decryptCalls=0
        AppLog.errors.clear()
        val poll=PollFixture(AapReadSingleMessage(connection,AapSsl(),handler))
        check(poll.poll() && poll.quits==1 && poll.clean==false && poll.pollHandler!!.queued==0) {
            "Timed-out body must quit the poll session without queuing another read"
        }
        check(input.consumed==4+prefix && input.failures==1)
        check(AapMessageIncoming.decryptCalls==0 && handler.delivered==0)
        check(AppLog.errors.single().contains("body read returned 0 of 4566"))
        connection.disconnect()
    }
    val eof=BodyInput(300, timeout=false)
    val connection=SocketProjectionConnection(BodySocket(eof),Context())
    AppLog.errors.clear(); AapMessageIncoming.decryptCalls=0
    val eofPoll=PollFixture(AapReadSingleMessage(connection,AapSsl(),AapMessageHandler()))
    check(eofPoll.poll() && eofPoll.quits==1 && eofPoll.clean==false && eofPoll.pollHandler!!.queued==0)
    check(eof.failures==1 && AapMessageIncoming.decryptCalls==0)
    connection.disconnect()
    val complete=BodyInput(4566, timeout=true)
    val good=SocketProjectionConnection(BodySocket(complete),Context())
    val handler=AapMessageHandler()
    AppLog.errors.clear(); AapMessageIncoming.decryptCalls=0
    val goodPoll=PollFixture(AapReadSingleMessage(good,AapSsl(),handler))
    check(goodPoll.poll() && goodPoll.quits==0 && goodPoll.pollHandler!!.queued==1)
    check(complete.consumed==4570 && complete.failures==0)
    check(AapMessageIncoming.decryptCalls==1 && handler.delivered==1 && AppLog.errors.isEmpty())
    good.disconnect()
    println("PASS: AA module 6; body 4566; timeout after 0/300/4565 bytes is fatal and visible; EOF fatal; full body delivered")
}
private class BodyInput(prefix:Int,private val timeout:Boolean):InputStream() {
    private val bytes=byteArrayOf(1,0x0b,0x11,0xd6.toByte())+ByteArray(prefix){0x55}
    var consumed=0
    var failures=0
    override fun read():Int=error("bulk reads required")
    override fun read(b:ByteArray,off:Int,len:Int):Int {
        if(consumed==bytes.size) {
            failures++
            check(failures==1) {"Damaged stream was read again"}
            if(timeout) throw SocketTimeoutException("synthetic body timeout") else return -1
        }
        val n=minOf(len,bytes.size-consumed)
        bytes.copyInto(b,off,consumed,consumed+n); consumed+=n;return n
    }
}
private class BodySocket(private val input:InputStream):Socket() {
    private var closed=false
    override fun getInetAddress()=InetAddress.getLoopbackAddress()
    override fun getPort()=5277
    override fun isConnected()=true
    override fun isClosed()=closed
    override fun close(){closed=true}
    override fun setSoTimeout(ms:Int){}
    override fun getInputStream()=input
    override fun getOutputStream()=ByteArrayOutputStream()
}
