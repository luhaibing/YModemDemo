package com.mercer.demo

import android.graphics.Point
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bw.yml.YModem
import com.bw.yml.YModemListener
import com.mercer.demo.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.math.RoundingMode
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.apply {
            btUpgrade.setOnClickListener {
                val height = resources.displayMetrics.heightPixels
                val point = Point()
                windowManager.defaultDisplay.getRealSize(point)
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                val bounds = windowManager.currentWindowMetrics.bounds
                println(height)
                println(displayMetrics.heightPixels)
                println(point.y)
                println(bounds.height())
                println(file.absolutePath)
                //  yModem?.start(null)
                yModem?.stop()
                yModem = YModem.Builder()
                    .with(
                        this@MainActivity,
                        Int.MAX_VALUE, 50, -5
                    )
                    .filePath(file.absolutePath) //存放到手机的文件路径 stroge/0/.../xx.bin 这种路径
                    .fileName(file.name)
                    .checkMd5("") //Md5可以写可以不写 看自己的通讯协议
                    .sendSize(1024) //可以修改成你需要的大小
                    .callback(yModemListener)
                    .build()
                yModem?.start(null)
            }
            btStop.setOnClickListener {
                yModem?.stop()
            }
        }
    }

    private val file by lazy {
        File(externalCacheDir, "fox.jpg")
    }

    var yModem: YModem? = null

    val yModemListener by lazy {
        object : YModemListener {
            override fun onDataReady(data: ByteArray) {
                Log.e("TAG", "onDataReady : ${data.joinToString { it.toString(16) }}")
                handleData(data)
            }

            override fun onProgress(currentSent: Int, total: Int) {
                val progress = currentSent.toBigDecimal().divide(total.toBigDecimal(), 2, RoundingMode.UP)
                render("进度:${progress}")
                Log.e("TAG", "currentSent : $currentSent ; total : $total. ; progress : $progress")
            }

            override fun onSuccess() {
                render("成功")
            }

            override fun onFailed(reason: String?) {
                Log.e("TAG", "onFailed : $reason .")
                render("失败")
            }

        }
    }

    private var retries: Int = 0

    private fun handleData(value: ByteArray?) {
        value ?: return
        when (value.size) {
            133 -> {
                if (value[3].toInt() == 0) {
                    // 发送结束
                    lifecycleScope.launch {
                        delay(250.milliseconds)
                        yModem?.onReceiveData(byteArrayOf(0x06))
                    }
                } else {
                    // 发送文件名
                    lifecycleScope.launch {
                        // 模拟分开应答 ACK，C
                        delay(250.milliseconds)
                        yModem?.onReceiveData(byteArrayOf(0x06))
                        delay(250.milliseconds)
                        yModem?.onReceiveData(byteArrayOf(0x43))

                        /*
                        // 模拟同时应答 ACK，C
                        delay(250.milliseconds)
                        yModem?.onReceiveData(byteArrayOf(0x06,0x43))
                        */
                    }
                }
            }

            1029 -> {
                lifecycleScope.launch {
                    retries++
                    delay(50.milliseconds)
                    if (retries < 10) {
                        // 模拟拒绝
                        yModem?.onReceiveData(byteArrayOf(0x15))
                    } else {
                        // 模拟同意
                        yModem?.onReceiveData(byteArrayOf(0x06))
                        retries = 0
                    }
                }
            }

            else -> {
                yModem?.onReceiveData(byteArrayOf(0x06, 0x43))
            }
        }
    }

    private fun render(value: String) {
        runOnUiThread {
            binding.tvProgress.text = value
        }
    }

}