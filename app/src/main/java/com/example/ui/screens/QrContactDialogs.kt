package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.domain.QrBitmapGenerator
import com.example.domain.QrContactCodec
import com.example.domain.QrContactPayload
import com.example.domain.QrContactValidationResult
import com.example.domain.TrustedContact
import com.example.ui.components.SectionEyebrow
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.WarmMistBackground
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Dialog displaying the user's personal SoloSignal AI QR code so another SoloSignal AI user
 * can scan it to add them as a trusted emergency contact.
 */
@Composable
fun PersonalQrCodeDialog(
    userUid: String,
    userDisplayName: String,
    userEmail: String,
    initialPhone: String = "",
    onDismiss: () -> Unit
) {
    var editableName by remember(userDisplayName) {
        mutableStateOf(
            userDisplayName.trim().ifBlank {
                if (userEmail.contains("@")) userEmail.substringBefore("@") else "SoloSignal User"
            }
        )
    }
    var optionalPhone by remember(initialPhone) { mutableStateOf(initialPhone) }
    var regenerationCount by remember { mutableIntStateOf(0) }

    val payload = remember(userUid, editableName, userEmail, optionalPhone, regenerationCount) {
        QrContactCodec.createPersonalPayload(
            uid = userUid,
            displayName = editableName,
            email = userEmail,
            phone = optionalPhone
        )
    }

    val encodedQrUri = remember(payload) {
        QrContactCodec.encode(payload)
    }

    val qrBitmap = remember(encodedQrUri) {
        QrBitmapGenerator.generateImageBitmap(encodedQrUri, sizePx = 640)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CardSurfaceWhite,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("personal_qr_code_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(LavenderSoftSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode2,
                                contentDescription = null,
                                tint = DeepCharcoal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            SectionEyebrow(text = "PERSONAL CONTACT QR")
                            Text(
                                text = "My Emergency Contact QR",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_personal_qr_icon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close personal QR dialog",
                            tint = DeepCharcoal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Let the other person scan this code to add you as an emergency contact.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DeepCharcoal,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("personal_qr_instruction_text")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .border(1.dp, HairlineDivider, RoundedCornerShape(20.dp))
                        .padding(14.dp)
                        .testTag("personal_qr_code_box"),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Personal emergency contact QR code for ${payload.displayName}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("personal_qr_code_image")
                        )
                    } else {
                        Text(
                            text = "Enter your name or account email to generate QR code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = WarmMistBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = payload.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("personal_qr_display_name_text")
                        )
                        if (payload.email.isNotBlank()) {
                            Text(
                                text = payload.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("personal_qr_email_text")
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Invite code ${payload.inviteToken.takeLast(6).uppercase()} • Expires in 15 min",
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralSlate,
                            modifier = Modifier.testTag("personal_qr_token_badge")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = editableName,
                    onValueChange = { editableName = it },
                    label = { Text("Your Display Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("personal_qr_name_input")
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = optionalPhone,
                    onValueChange = { optionalPhone = it },
                    label = { Text("Your Phone Number (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("personal_qr_phone_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { regenerationCount += 1 },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("refresh_qr_code_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh Code", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("close_personal_qr_button")
                    ) {
                        Text("Close", color = Color.White, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Dialog providing camera-based QR scanning (via CameraX + Google ML Kit Barcode Scanning),
 * runtime camera permission handling, alignment frame overlay, and validation error recovery.
 */
@Composable
fun ScanQrCodeDialog(
    scannerUid: String?,
    scannerEmail: String?,
    existingContact: TrustedContact?,
    onValidQrScanned: (QrContactPayload) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }
    var scanErrorMessage by remember { mutableStateOf<String?>(null) }
    var isScanningActive by remember { mutableStateOf(true) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
        if (granted) {
            scanErrorMessage = null
            isScanningActive = true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequestedOnce) {
            permissionRequestedOnce = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun handleDecodedRawValue(rawValue: String) {
        if (!isScanningActive) return
        when (
            val validation = QrContactCodec.decodeAndValidate(
                rawQrContent = rawValue,
                scannerUid = scannerUid,
                scannerEmail = scannerEmail,
                existingContact = existingContact
            )
        ) {
            is QrContactValidationResult.Valid -> {
                isScanningActive = false
                scanErrorMessage = null
                onValidQrScanned(validation.payload)
            }
            is QrContactValidationResult.DuplicateContact -> {
                isScanningActive = false
                scanErrorMessage = validation.message
            }
            is QrContactValidationResult.Expired -> {
                isScanningActive = false
                scanErrorMessage = validation.message
            }
            is QrContactValidationResult.SelfScanRejected -> {
                isScanningActive = false
                scanErrorMessage = validation.message
            }
            is QrContactValidationResult.UnsupportedVersion -> {
                isScanningActive = false
                scanErrorMessage = validation.message
            }
            is QrContactValidationResult.InvalidFormat -> {
                isScanningActive = false
                scanErrorMessage = validation.message
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CardSurfaceWhite,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("scan_qr_code_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(LavenderSoftSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = DeepCharcoal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            SectionEyebrow(text = "QR CONTACT SCANNER")
                            Text(
                                text = "Scan Emergency Contact QR",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_scan_qr_icon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close QR scanner",
                            tint = DeepCharcoal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Align the QR code inside the frame.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DeepCharcoal,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scan_qr_instruction_text")
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (!hasCameraPermission) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = WarmMistBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("camera_permission_denied_container")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = DeepCharcoal,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Camera permission is required to scan QR codes",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Grant camera access to scan another SoloSignal AI user's personal QR code, or close this scanner to enter their details manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralSlate,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DeepCharcoal,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("request_camera_permission_button")
                            ) {
                                Text("Grant Camera Permission", color = Color.White)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(DeepCharcoal)
                            .border(2.dp, HairlineDivider, RoundedCornerShape(22.dp))
                            .testTag("qr_camera_preview_container"),
                        contentAlignment = Alignment.Center
                    ) {
                        CameraQrPreviewSurface(
                            isScanningActive = isScanningActive,
                            onBarcodeDetected = { rawValue ->
                                handleDecodedRawValue(rawValue)
                            }
                        )

                        // Visual scanning alignment frame overlay
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(36.dp)
                                .testTag("qr_alignment_frame_overlay")
                        ) {
                            val frameSize = minOf(size.width, size.height)
                            val left = (size.width - frameSize) / 2f
                            val top = (size.height - frameSize) / 2f
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.88f),
                                topLeft = Offset(left, top),
                                size = ComposeSize(frameSize, frameSize),
                                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }

                if (scanErrorMessage != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CoralSoftSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CoralUrgentHighlight, RoundedCornerShape(16.dp))
                            .testTag("scan_qr_error_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = CoralUrgentHighlight,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = scanErrorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = DeepCharcoal,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scan_qr_error_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scanErrorMessage = null
                            isScanningActive = true
                        },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("retry_scan_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Scan", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("close_scan_qr_button")
                    ) {
                        Text("Close", color = Color.White, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraQrPreviewSurface(
    isScanningActive: Boolean,
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                barcodeScanner.close()
            } catch (_: Exception) {
            }
            try {
                analysisExecutor.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && isScanningActive) {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                barcodeScanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        val firstRaw = barcodes.firstOrNull {
                                            !it.rawValue.isNullOrBlank()
                                        }?.rawValue
                                        if (!firstRaw.isNullOrBlank()) {
                                            onBarcodeDetected(firstRaw)
                                        }
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
            } catch (_: Exception) {
            }
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Confirmation dialog shown after scanning a valid SoloSignal AI QR code.
 * Requires explicit user confirmation before saving the emergency contact.
 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmScannedContactDialog(
    scannedPayload: QrContactPayload,
    selectedRelationship: String,
    onRelationshipChange: (String) -> Unit,
    onConfirmAndAdd: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CardSurfaceWhite,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("confirm_scanned_contact_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MintSoftSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        SectionEyebrow(text = "QR CODE VERIFIED")
                        Text(
                            text = "Add Emergency Contact?",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal,
                            modifier = Modifier.testTag("confirm_scanned_contact_title")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = WarmMistBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column {
                            Text(
                                text = "Name",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeutralSlate
                            )
                            Text(
                                text = scannedPayload.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal,
                                modifier = Modifier.testTag("confirm_scanned_contact_name")
                            )
                        }

                        if (scannedPayload.email.isNotBlank()) {
                            HorizontalDivider(color = HairlineDivider)
                            Column {
                                Text(
                                    text = "Contact Identifier (SoloSignal Account Email)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeutralSlate
                                )
                                Text(
                                    text = scannedPayload.email,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = DeepCharcoal,
                                    modifier = Modifier.testTag("confirm_scanned_contact_email")
                                )
                            }
                        }

                        if (scannedPayload.phone.isNotBlank()) {
                            HorizontalDivider(color = HairlineDivider)
                            Column {
                                Text(
                                    text = "Phone Number",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeutralSlate
                                )
                                Text(
                                    text = scannedPayload.phone,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = DeepCharcoal,
                                    modifier = Modifier.testTag("confirm_scanned_contact_phone")
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Relationship",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeutralSlate
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Family", "Partner", "Friend", "Caregiver").forEach { rel ->
                        FilterChip(
                            selected = selectedRelationship.equals(rel, ignoreCase = true),
                            onClick = { onRelationshipChange(rel) },
                            label = { Text(rel, maxLines = 1, softWrap = false) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepCharcoal,
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.testTag("confirm_qr_relationship_${rel.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Confirming saves this person as your emergency contact and checks their SoloSignal AI account so emergency alerts can be sent to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("cancel_qr_contact_button")
                    ) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = onConfirmAndAdd,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("confirm_and_add_qr_contact_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Confirm and Add", color = Color.White, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
