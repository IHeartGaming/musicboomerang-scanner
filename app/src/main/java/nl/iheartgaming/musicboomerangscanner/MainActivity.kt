package nl.iheartgaming.musicboomerangscanner

import android.Manifest
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.iheartgaming.musicboomerangscanner.ui.theme.MusicBoomerangScannerTheme
import org.json.JSONArray
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

data class Want(
    val barcode: String,
    val artist: String,
    val album: String,
    val wants: String
)

val cookieJar = SessionCookieJar()
val httpClient = okhttp3.OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", "MusicBoomerangScanner/${BuildConfig.VERSION_NAME}")
            .build()
        chain.proceed(request)
    }
    .build()

class MainActivity : ComponentActivity() {
    private var loggedIn by mutableStateOf(false)
    private var cameraActive by mutableStateOf(false)
    private var lastMatch by mutableStateOf<Want?>(null)
    private var barcodeText by mutableStateOf("")
    private var hasSubmitted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MusicBoomerangScannerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF1C2C4D),
                    topBar = {
                        if (!cameraActive) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(horizontal = 16.dp)
                                    .padding(WindowInsets.statusBars.asPaddingValues()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "MusicBoomerang Scanner",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    if (!loggedIn) {
                        LoginScreen(
                            modifier = Modifier.padding(innerPadding),
                            onLogin = { user, pass, callback -> login(user, pass, callback) },
                            playSound = { resId -> this@MainActivity.playSound(resId) }
                        )
                    } else if (cameraActive) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraScanView(
                                onBarcodeDetected = { code ->
                                    if (!hasSubmitted) {
                                        hasSubmitted = true
                                        handleBarcode(code)
                                        cameraActive = false
                                    }
                                },
                            )

                            IconButton(
                                onClick = {
                                    cameraActive = false
                                    hasSubmitted = false
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(WindowInsets.statusBars.asPaddingValues())
                                    .padding(16.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close camera",
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        MainScreen(
                            modifier = Modifier.padding(innerPadding),
                            barcodeText = barcodeText,
                            onBarcodeSubmitted = { barcode -> handleBarcode(barcode) },
                            onBarcodeTextChange = { barcodeText = it },
                            lastMatch = lastMatch,
                            onCameraButtonClick = {
                                cameraActive = true
                                hasSubmitted = false
                            }
                        )
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun handleBarcode(barcode: String) {
        val cleaned = barcode.replace(Regex("[\\s-]"), "")
        if (!cleaned.all { it.isDigit() }) {
            playSound(R.raw.error_sound)
            triggerVibration(100)
            lastMatch = null
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://musicboomerang.com/API/wants?upc=$cleaned&months=3&credits"
                val req = okhttp3.Request.Builder().url(url).get().build()
                val resp = httpClient.newCall(req).execute()
                val data = resp.body?.string() ?: "[]"

                val json = JSONArray(data)
                if (json.getJSONObject(0).getInt("items") == 0) {
                    withContext(Dispatchers.Main) {
                        playSound(R.raw.fail_sound)
                        triggerVibration(100)
                        lastMatch = null
                    }
                } else {
                    val o = json.getJSONObject(0)
                    val want = Want(
                        barcode = cleaned,
                        artist = o.getString("artist"),
                        album = o.getString("title"),
                        wants = o.getInt("want_count").toString()
                    )
                    withContext(Dispatchers.Main) {
                        playSound(R.raw.success_sound)
                        triggerVibration(1000)
                        lastMatch = want
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    playSound(R.raw.error_sound)
                    triggerVibration(100)
                    lastMatch = null
                }
            }
        }
    }

    private fun playSound(resId: Int) {
        val mediaPlayer = MediaPlayer.create(this, resId)
        mediaPlayer.start()
        mediaPlayer.setOnCompletionListener { it.release() }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun triggerVibration(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(duration)
    }

    private fun login(
        user: String,
        pass: String,
        callback: (Boolean, String?) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val getReq = okhttp3.Request.Builder()
                    .url("https://musicboomerang.com/")
                    .get()
                    .build()
                httpClient.newCall(getReq).execute().close()

                val form = okhttp3.FormBody.Builder()
                    .add("ReturnUrl", "")
                    .add("PostBackAction", "SignIn")
                    .add("Username", user)
                    .add("Password", pass)
                    .build()

                val postReq = okhttp3.Request.Builder()
                    .url("https://musicboomerang.com/processlogin.php")
                    .post(form)
                    .build()

                val resp = httpClient.newCall(postReq).execute()
                val body = resp.body?.string() ?: ""

                if ("Login incorrect" in body) {
                    withContext(Dispatchers.Main) { callback(false, "Login incorrect") }
                } else {
                    loggedIn = true
                    withContext(Dispatchers.Main) { callback(true, null) }
                }

            } catch (_: Exception) {
                withContext(Dispatchers.Main) { callback(false, "Network error") }
            }
        }
    }
}

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    onLogin: (String, String, (Boolean, String?) -> Unit) -> Unit,
    playSound: (Int) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val passwordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val performLogin = {
        errorMsg = null
        onLogin(username, password) { success, error ->
            if (success) {
                playSound(R.raw.success_sound)
            } else {
                playSound(R.raw.error_sound)
                errorMsg = error
            }
        }
        keyboardController?.hide()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            placeholder = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { passwordFocusRequester.requestFocus() }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedPlaceholderColor = Color.Gray,
                unfocusedPlaceholderColor = Color.Gray
            )
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (username.isNotEmpty() && password.isNotEmpty()) performLogin() }
            ),
            modifier = Modifier.focusRequester(passwordFocusRequester),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedPlaceholderColor = Color.Gray,
                unfocusedPlaceholderColor = Color.Gray
            )
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { if (username.isNotEmpty() && password.isNotEmpty()) performLogin() },
            enabled = username.isNotEmpty() && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (username.isNotEmpty() && password.isNotEmpty()) Color(
                    0xFFFABD08
                )
                else Color(0xFFCCCCCC)
            )
        ) {
            Text(
                "Login",
                color = if (username.isNotEmpty() && password.isNotEmpty()) Color(0xFF1C2C4D) else Color(
                    0xFF666666
                )
            )
        }

        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(msg, color = Color.Red)
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier,
    barcodeText: String,
    onBarcodeTextChange: (String) -> Unit,
    onBarcodeSubmitted: (String) -> Unit,
    lastMatch: Want?,
    onCameraButtonClick: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(8.dp)
        ) {
            BasicTextField(
                value = barcodeText,
                onValueChange = { onBarcodeTextChange(it) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(Color.White, shape = RoundedCornerShape(6.dp))
                    .border(1.dp, Color.Black, shape = RoundedCornerShape(6.dp))
                    .padding(8.dp)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Enter) {
                            if (barcodeText.isNotEmpty()) {
                                onBarcodeSubmitted(barcodeText.trim())
                                onBarcodeTextChange("")
                                keyboardController?.hide()
                            }
                            true // consume event
                        } else {
                            false
                        }
                    },
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 36.sp,
                    lineHeight = 40.sp
                ),
                enabled = true,
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (barcodeText.isNotEmpty()) {
                            onBarcodeSubmitted(barcodeText.trim())
                            onBarcodeTextChange("")
                            keyboardController?.hide()
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onCameraButtonClick,
                modifier = Modifier
                    .height(56.dp)
                    .aspectRatio(1f)
                    .background(Color(0xFFFABD08), shape = RoundedCornerShape(6.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Scan Barcode",
                    tint = Color(0xFF1C2C4D)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onBarcodeSubmitted(barcodeText)
                onBarcodeTextChange("")
                keyboardController?.hide()
            },
            enabled = barcodeText.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (barcodeText.isNotEmpty()) Color(0xFFFABD08)
                else Color(0xFFCCCCCC)
            )
        ) {
            Text(
                "Submit",
                color = if (barcodeText.isNotEmpty()) Color(0xFF1C2C4D) else Color(
                    0xFF666666
                )
            )
        }

        lastMatch?.let { want ->
            Spacer(Modifier.height(12.dp))
            Text(want.album, color = Color.White)
            Text("by ${want.artist}", color = Color.White)
            Text("Wants: ${want.wants}", color = Color.White)
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScanView(
    onBarcodeDetected: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = androidx.camera.view.PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                            Barcode.FORMAT_UPC_A,
                            Barcode.FORMAT_UPC_E,
                            Barcode.FORMAT_EAN_8,
                            Barcode.FORMAT_EAN_13
                        )
                        .build()
                )

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull()?.rawValue?.let { code ->
                                    // Submit barcode and close camera
                                    onBarcodeDetected(code)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analyzer
                )

            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
