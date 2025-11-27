package nl.iheartgaming.musicboomerangscanner

import android.Manifest
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
    private var lastMatch by mutableStateOf<Want?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MusicBoomerangScannerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF1C2C4D),
                    topBar = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp),
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
                ) { innerPadding ->
                    if (!loggedIn) {
                        LoginScreen(
                            modifier = Modifier.padding(innerPadding),
                            onLogin = { user, pass, callback -> login(user, pass, callback) },
                            playSound = { resId -> this@MainActivity.playSound(resId) }
                        )
                    } else {
                        MainScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBarcodeSubmitted = { barcode -> handleBarcode(barcode) },
                            lastMatch = lastMatch
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
    onBarcodeSubmitted: (String) -> Unit,
    lastMatch: Want?
) {
    var barcodeText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicTextField(
            value = barcodeText,
            onValueChange = { barcodeText = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, shape = RoundedCornerShape(6.dp))
                .border(1.dp, Color.Black, shape = RoundedCornerShape(6.dp))
                .padding(8.dp)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key == Key.Enter) {
                        if (barcodeText.isNotEmpty()) {
                            onBarcodeSubmitted(barcodeText.trim())
                            barcodeText = ""
                            keyboardController?.hide()
                        }
                        true // consume event
                    } else {
                        false
                    }
                },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = true,
            keyboardActions = KeyboardActions(
                onDone = {
                    if (barcodeText.isNotEmpty()) {
                        onBarcodeSubmitted(barcodeText.trim())
                        barcodeText = ""
                        keyboardController?.hide()
                    }
                }
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                onBarcodeSubmitted(barcodeText)
                barcodeText = ""
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
