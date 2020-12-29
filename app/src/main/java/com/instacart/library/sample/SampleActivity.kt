package com.instacart.library.sample

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.instacart.library.sample.databinding.ActivitySampleBinding
import com.instacart.library.truetime.legacy.TrueTimeRx
import com.instacart.library.truetime.sntp.SntpImpl
import com.instacart.library.truetime.time.TrueTime2
import com.instacart.library.truetime.time.TrueTimeImpl
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

@SuppressLint("SetTextI18n")
@RequiresApi(Build.VERSION_CODES.O)
class SampleActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySampleBinding
    private val disposables = CompositeDisposable()

    private lateinit var trueTime: TrueTime2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySampleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "True Time Demo"

        binding.btnRefresh.setOnClickListener { refreshTime() }
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.clear()
    }

    private fun refreshTime() {
        binding.deviceTime.text = "Device Time: (loading...)"

        kickOffTruetimeCoroutines()
        kickOffTrueTimeRx()

        binding.deviceTime.text = "Device Time: ${formatDate(Date())}"
    }

    private fun kickOffTruetimeCoroutines() {
        binding.truetimeNew.text = "TrueTime (Coroutines): (loading...)"

        CoroutineScope(Dispatchers.Main.immediate).launch {

            if (!::trueTime.isInitialized) {
                trueTime = TrueTimeImpl(SntpImpl())
            }

            binding.truetimeNew.text = "TrueTime (Coroutines): ${formatDate(trueTime.now())}"
        }
    }

    private fun kickOffTrueTimeRx() {
        binding.truetimeLegacy.text = "TrueTime (Rx) : (loading...)"

        val d = TrueTimeRx()
            .withConnectionTimeout(31428)
            .withRetryCount(100)
//            .withSharedPreferencesCache(this)
            .withLoggingEnabled(true)
            .initializeRx("time.google.com")
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ date ->
                binding.truetimeLegacy.text = "TrueTime (Rx) : ${formatDate(date)}"
            }, {
                Log.e("Demo", "something went wrong when trying to initializeRx TrueTime", it)
            })

        disposables.add(d)
    }

    private fun formatDate(date: Date): String {
        return Instant
            .ofEpochMilli(date.time)
            .atZone(ZoneId.of("America/Los_Angeles"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}
