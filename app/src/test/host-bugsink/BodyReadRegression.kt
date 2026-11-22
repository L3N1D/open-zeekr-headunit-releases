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
        AppLog.errors.clear(); AppLog.causes.clear()
        val poll=PollFixture(AapReadSingleMessage(connection,AapSsl(),handler))
        check(poll.poll() && poll.quits==1 && poll.clean==false && poll.pollHandler!!.queued==0) {
            "Timed-out body must quit the poll session without queuing another read"
        }
        check(input.consumed==4+prefix && input.failures==1)
        check(AapMessageIncoming.decryptCalls==0 && handler.delivered==0)
        check(AppLog.errors.single().contains("body read returned 0 of 4566"))
        check(AppLog.errors.single().contains("received=$prefix, requested=4566, timeoutMs=10000, full=true"))
        check(AppLog.causes.single() === input.failure)
        check(connection.lastReadFailure?.bytesRead==prefix)
        connection.disconnect()
    }
    val eof=BodyInput(300, timeout=false)
    val connection=SocketProjectionConnection(BodySocket(eof),Context())
    AppLog.errors.clear(); AapMessageIncoming.decryptCalls=0
    val eofPoll=PollFixture(AapReadSingleMessage(connection,AapSsl(),AapMessageHandler()))
    check(eofPoll.poll() && eofPoll.quits==1 && eofPoll.clean==false && eofPoll.pollHandler!!.queued==0)
    check(eof.failures==1 && AapMessageIncoming.decryptCalls==0)
    check(AppLog.errors.single().contains("received=300, requested=4566"))
    check(connection.lastReadFailure?.cause is EOFException)
    connection.disconnect()
    val complete=BodyInput(4566, timeout=true)
    val good=SocketProjectionConnection(BodySocket(complete),Context())
    val handler=AapMessageHandler()
    AppLog.errors.clear(); AapMessageIncoming.decryptCalls=0
    val goodPoll=PollFixture(AapReadSingleMessage(good,AapSsl(),handler))
    check(goodPoll.poll() && goodPoll.quits==0 && goodPoll.pollHandler!!.queued==1)
    check(complete.consumed==4570 && complete.failures==0)
    check(AapMessageIncoming.decryptCalls==1 && handler.delivered==1 && AppLog.errors.isEmpty())
    check(good.lastReadFailure==null)
    good.disconnect()
    for(error in listOf(IOException("synthetic I/O fault"),SocketException("synthetic reset"),
        SocketTimeoutException("synthetic transferred bytes").apply { bytesTransferred=19 })) {
        val transferred=(error as? InterruptedIOException)?.bytesTransferred ?: 0
        val input=BodyInput(300,timeout=true,error=error,interruptedBytes=transferred)
        val conn=SocketProjectionConnection(BodySocket(input),Context())
        AppLog.errors.clear(); AppLog.causes.clear(); AapMessageIncoming.decryptCalls=0
        val poll=PollFixture(AapReadSingleMessage(conn,AapSsl(),AapMessageHandler()))
        check(poll.poll() && poll.quits==1 && poll.clean==false && poll.pollHandler!!.queued==0)
        check(conn.lastReadFailure?.bytesRead==300+transferred && input.consumed==304+transferred)
        check(AppLog.errors.any {it.contains("received=${300+transferred}, requested=4566")})
        check(AppLog.causes.any {it === error})
        if(error is SocketException) check(AppLog.errors.first().contains("received=300, requested=4566"))
        check(AapMessageIncoming.decryptCalls==0 && input.failures==1)
        conn.disconnect()
    }
    for(headerPrefix in listOf(0,2)) {
        val input=BodyInput(0,timeout=true,headerBytes=headerPrefix)
        val conn=SocketProjectionConnection(BodySocket(input),Context())
        AppLog.errors.clear(); AppLog.causes.clear()
        val poll=PollFixture(AapReadSingleMessage(conn,AapSsl(),AapMessageHandler()))
        check(poll.poll() && poll.quits==1 && poll.pollHandler!!.queued==0)
        check(AppLog.errors.single().contains("received=$headerPrefix, requested=4, timeoutMs=15000"))
        check(AppLog.causes.single() === input.failure)
        conn.disconnect()
    }
    val headerError=IOException("header I/O failure")
    val headerInput=BodyInput(0,timeout=true,error=headerError,headerBytes=0)
    val headerConn=SocketProjectionConnection(BodySocket(headerInput),Context())
    AppLog.errors.clear(); AppLog.causes.clear()
    val headerPoll=PollFixture(AapReadSingleMessage(headerConn,AapSsl(),AapMessageHandler()))
    check(headerPoll.poll() && headerPoll.quits==1 && headerPoll.pollHandler!!.queued==0)
    check(AppLog.errors.single().contains("received=0, requested=4"))
    check(AppLog.causes.single() === headerError)
    headerConn.disconnect()
    val totalInput=BodyInput(2,timeout=true,flags=0x09)
    val totalConn=SocketProjectionConnection(BodySocket(totalInput),Context())
    AppLog.errors.clear(); AppLog.causes.clear(); AapMessageIncoming.decryptCalls=0
    val totalPoll=PollFixture(AapReadSingleMessage(totalConn,AapSsl(),AapMessageHandler()))
    check(totalPoll.poll() && totalPoll.quits==1 && totalPoll.pollHandler!!.queued==0)
    check(AppLog.errors.single().contains("fragment total"))
    check(AppLog.errors.single().contains("received=2, requested=4, timeoutMs=10000"))
    check(AppLog.causes.single() === totalInput.failure && AapMessageIncoming.decryptCalls==0)
    totalConn.disconnect()
    val closeInput=BodyInput(300,timeout=true,error=SocketException("local close"))
    val closeConn=SocketProjectionConnection(BodySocket(closeInput),Context())
    closeInput.beforeFailure={closeConn.disconnect()}
    AppLog.errors.clear(); AppLog.causes.clear()
    val closePoll=PollFixture(AapReadSingleMessage(closeConn,AapSsl(),AapMessageHandler()))
    check(closePoll.poll() && closePoll.quits==1 && closePoll.pollHandler!!.queued==0)
    check(AppLog.errors.isEmpty() && closeConn.lastReadFailure==null)
    val cleanEof=SocketProjectionConnection(BodySocket(BodyInput(0,timeout=false,headerBytes=0)),Context())
    AppLog.errors.clear()
    check(PollFixture(AapReadSingleMessage(cleanEof,AapSsl(),AapMessageHandler())).poll())
    check(AppLog.errors.isEmpty())
    cleanEof.disconnect()
    println("PASS: read diagnostics preserve original exception and byte count, including InterruptedIOException progress, EOF and header timeout")
    println("PASS: AA module 6; body 4566; timeout after 0/300/4565 bytes is fatal and visible; EOF fatal; full body delivered")
}
private class BodyInput(prefix:Int,private val timeout:Boolean,error:IOException?=null,
    private val interruptedBytes:Int=0,headerBytes:Int=4,flags:Int=0x0b):InputStream() {
    var beforeFailure:()->Unit = {}
    val failure=error ?: SocketTimeoutException("synthetic body timeout")
    private val bytes=byteArrayOf(1,flags.toByte(),0x11,0xd6.toByte()).take(headerBytes).toByteArray()+ByteArray(prefix){0x55}
    var consumed=0
    var failures=0
    override fun read():Int=error("bulk reads required")
    override fun read(b:ByteArray,off:Int,len:Int):Int {
        if(consumed>=bytes.size) {
            failures++
            check(failures==1) {"Damaged stream was read again"}
            beforeFailure()
            if(timeout) {
                repeat(interruptedBytes){b[off+it]=0x55}; consumed+=interruptedBytes
                throw failure
            } else return -1
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
