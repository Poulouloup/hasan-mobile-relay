package com.hasan.v1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hasan.v1.databinding.ActivityQrScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scanner QR minimal pour le pairing relay — CameraX + ML Kit barcode scanning.
 * Retourne le texte brut du premier QR détecté via [EXTRA_QR_TEXT] et se ferme
 * immédiatement (un seul scan par lancement, pas de mode continu).
 *
 * Écran volontairement minimal (juste la preview caméra + un texte d'aide) —
 * le design soigné (cadre de visée animé, retour haptique, etc.) est prévu à
 * l'étape 9 (refonte UI).
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService

    @Volatile
    private var handled = false

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_QR_ERROR, "permission_denied"))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val scannerOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(scannerOptions)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Échec de binding CameraX: ${e.message}")
                setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_QR_ERROR, "camera_bind_failed"))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        if (handled) return
        val text = barcodes.firstNotNullOfOrNull { it.rawValue } ?: return
        handled = true

        setResult(RESULT_OK, Intent().putExtra(EXTRA_QR_TEXT, text))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "QrScannerActivity"
        const val EXTRA_QR_TEXT = "qr_text"
        /** Raison de l'échec quand resultCode != RESULT_OK : "permission_denied" ou "camera_bind_failed". */
        const val EXTRA_QR_ERROR = "qr_error"
    }
}
