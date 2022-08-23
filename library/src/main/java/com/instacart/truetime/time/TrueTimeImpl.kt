package com.instacart.truetime.time

import com.instacart.truetime.TrueTimeEventListener
import com.instacart.truetime.NoOpEventListener
import com.instacart.truetime.sntp.Sntp
import com.instacart.truetime.sntp.SntpImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Date

class TrueTimeImpl(
    private val listener: TrueTimeEventListener = NoOpEventListener,
) : TrueTime {

    private val sntp: Sntp = SntpImpl()
    private val timeKeeper = TimeKeeper(sntp, listener)

    override fun initialized(): Boolean = timeKeeper.hasTheTime()

    override suspend fun sync(with: TrueTimeParameters): Job = withContext(Dispatchers.IO) {
        launch {
            while (true) {
                try {
                    initialize(with)
                } catch (e: Exception) {
                    listener.initializeFailed(e)
                }

              listener.nextInitializeIn(with.syncIntervalInMillis)
              delay(with.syncIntervalInMillis)
            }
        }
    }

    override fun initialize(with: TrueTimeParameters): Date {
        val ntpResult = init(with)
        timeKeeper.save(ntpResult = ntpResult)
        return timeKeeper.now()
    }

    override fun nowSafely(): Date {
        return if (timeKeeper.hasTheTime()) {
            nowTrueOnly()
        } else {
            listener.returningDeviceTime()
            Date()
        }
    }

    override fun nowTrueOnly(): Date {
        if (!initialized()) throw IllegalStateException("TrueTime was not initialized successfully yet")
        return timeKeeper.now()
    }

    //region private helpers

    /**
     * Initialize TrueTime with an ntp pool server address
     */
    private fun init(with: TrueTimeParameters): LongArray {
        listener.initialize(with)

        // resolve NTP pool -> single IPs
        val ntpResult = resolveNtpHostToIPs(with.ntpHostPool.first())
            // for each IP resolved
            .map { ipHost ->
                // 5 times against each IP
                (1..5)
                    .map { requestTime(with, ipHost) }
                    // collect the 5 results to list
                    .toList()
                    // filter least round trip delay to get single Result
                    .filterLeastRoundTripDelay()
            }
            // collect max 5 of the IPs in a list
            .take(5)
            // filter median clock offset to get single Result
            .filterMedianClockOffset()

        listener.initializeSuccess(ntpResult)
        return ntpResult
    }

    /**
     * resolve ntp host pool address to single IPs
     */
    @Throws(UnknownHostException::class)
    private fun resolveNtpHostToIPs(ntpHostAddress: String): List<String> {
        val ipList = InetAddress.getAllByName(ntpHostAddress).map { it.hostAddress }
        listener.resolvedNtpHostToIPs(ntpHostAddress, ipList)
        return ipList
    }

    private fun requestTime(
      with: TrueTimeParameters,
      ipHostAddress: String,
    ): LongArray {
        // retrying upto (default 50) times if necessary
        repeat(with.retryCountAgainstSingleIp - 1) {
            try {
                // request Time
                return sntpRequest(with, ipHostAddress)
            } catch (e: Exception) {
                listener.sntpRequestFailed(e)
            }
        }

        // last attempt
        listener.sntpRequestLastAttempt(ipHostAddress)
        return sntpRequest(with, ipHostAddress)
    }

    private fun sntpRequest(
      with: TrueTimeParameters,
      ipHostAddress: String,
    ): LongArray = sntp.requestTime(
        ntpHostAddress = ipHostAddress,
        rootDelayMax = with.rootDelayMax,
        rootDispersionMax = with.rootDispersionMax,
        serverResponseDelayMax = with.serverResponseDelayMax,
        timeoutInMillis = with.connectionTimeoutInMillis,
        listener = listener,
    )

    private fun List<LongArray>.filterLeastRoundTripDelay(): LongArray {
        return minByOrNull { sntp.roundTripDelay(it) }
            ?: throw IllegalStateException("Could not find any results from requestingTime")
    }

    private fun List<LongArray>.filterMedianClockOffset(): LongArray {
        val sortedList = this.sortedBy { sntp.clockOffset(it) }
        return sortedList[sortedList.size / 2]
    }

    //endregion
}
