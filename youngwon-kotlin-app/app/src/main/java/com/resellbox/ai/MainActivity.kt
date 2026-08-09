package com.resellbox.ai

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.resellbox.ai.data.TFLiteDetector
import com.resellbox.ai.databinding.ActivityMainBinding
import com.resellbox.ai.measure.BoxMeasurer
import com.resellbox.ai.risk.RiskScorer
import com.resellbox.ai.ui.ResultRenderer
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val measurer = BoxMeasurer()

    // 온디바이스 추론기 — 모델(assets/model.tflite)이 없으면 null.
    private var detector: TFLiteDetector? = null
    private var detectorError: String? = null

    private var currentBitmap: Bitmap? = null
    private var cameraUri: Uri? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { loadBitmap(it) } }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) cameraUri?.let { loadBitmap(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 모델 로드 (없거나 실패해도 앱은 켜짐 — UI 확인 가능)
        detector = try {
            TFLiteDetector(this)
        } catch (e: Exception) {
            detectorError = "Failed to load model: ${e.message}\n" +
                "Make sure assets/model.tflite and labels.txt are present."
            null
        }

        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnAnalyze.setOnClickListener { analyze() }
        binding.btnAnalyze.isEnabled = false
        showHint(detectorError ?: getString(R.string.hint_start))
    }

    private fun launchCamera() {
        val file = File.createTempFile("capture_", ".jpg", cacheDir)
        cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePhoto.launch(cameraUri!!)
    }

    private fun loadBitmap(uri: Uri) {
        try {
            val bmp = decodeScaled(uri, 1024)
            currentBitmap = bmp
            binding.imgPreview.setImageBitmap(bmp)
            binding.btnAnalyze.isEnabled = true
            showHint(getString(R.string.hint_analyze))
        } catch (e: Exception) {
            showHint("Failed to load image: ${e.message}")
        }
    }

    private fun decodeScaled(uri: Uri, maxDim: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        val longSide = max(opts.outWidth, opts.outHeight)
        while (longSide / sample > maxDim) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: throw IllegalStateException("decode 실패")
        return raw.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun analyze() {
        val bmp = currentBitmap ?: return
        val det = detector
        if (det == null) {
            showHint(detectorError ?: "Model not ready.")
            return
        }
        setBusy(true)
        Thread {
            val result = det.detect(bmp)
            runOnUiThread {
                setBusy(false)
                when (result) {
                    is TFLiteDetector.Result.Error -> showHint("⚠ ${result.message}")
                    is TFLiteDetector.Result.Ok -> {
                        val measured = measurer.measure(result.predictions, result.image)
                        val outcome = RiskScorer.score(measured)
                        binding.imgPreview.setImageBitmap(
                            ResultRenderer.draw(bmp, result.predictions)
                        )
                        showResult(outcome)
                    }
                }
            }
        }.start()
    }

    /** 힌트/에러는 단순 텍스트로, 결과 카드는 숨긴다. */
    private fun showHint(text: String) {
        binding.txtResult.visibility = View.VISIBLE
        binding.txtResult.text = text
        binding.resultCard.visibility = View.GONE
    }

    /** 분석 결과를 디자인 카드로 표시한다. */
    private fun showResult(o: RiskScorer.Outcome) {
        binding.txtResult.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE

        val color = riskColor(o.risk)
        binding.badge.backgroundTintList = ColorStateList.valueOf(color)

        binding.txtDamage.text = o.topType?.replace("_", " ")?.uppercase()
            ?: "NO DAMAGE"
        binding.txtRisk.text = riskWord(o.risk)
        binding.txtSize.text = o.topLengthCm?.let { "~%.1f cm".format(it) } ?: "—"
    }

    private fun riskColor(r: RiskScorer.Risk): Int = when (r) {
        RiskScorer.Risk.LOW -> 0xFF2E7D32.toInt()      // 초록
        RiskScorer.Risk.CAUTION -> 0xFFEF6C00.toInt()  // 주황
        RiskScorer.Risk.HIGH -> 0xFFC62828.toInt()     // 빨강
    }

    private fun riskWord(r: RiskScorer.Risk): String = when (r) {
        RiskScorer.Risk.LOW -> "LOW RISK"
        RiskScorer.Risk.CAUTION -> "CAUTION"
        RiskScorer.Risk.HIGH -> "HIGH RISK"
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnAnalyze.isEnabled = !busy && currentBitmap != null
        binding.btnGallery.isEnabled = !busy
        binding.btnCamera.isEnabled = !busy
        if (busy) showHint(getString(R.string.hint_busy))
    }

    override fun onDestroy() {
        detector?.close()
        super.onDestroy()
    }
}
