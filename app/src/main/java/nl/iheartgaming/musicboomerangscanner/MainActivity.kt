package nl.iheartgaming.musicboomerangscanner

import android.Manifest
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.iheartgaming.musicboomerangscanner.ui.theme.MusicBoomerangScannerTheme
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class Want(
    val barcode: String,
    val artist: String,
    val album: String,
    val wants: String
)

class MainActivity : ComponentActivity() {

    private val wantList = mutableListOf<Want>()
    private var lastMatch by mutableStateOf<Want?>(null)
    private var fileLoaded by mutableStateOf(false)

    private val chooseFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handleFileUri(it) }
        }

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
                            TextButton(
                                onClick = {
                                    chooseFileLauncher.launch(
                                        arrayOf(
                                            "text/csv",
                                            "text/comma-separated-values"
                                        )
                                    )
                                },
                                enabled = !fileLoaded
                            ) {
                                Text(
                                    "Load",
                                    color = if (fileLoaded) Color.Gray else Color.White
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        enabled = fileLoaded,
                        onBarcodeSubmitted = { barcode -> handleBarcode(barcode) },
                        lastMatch = lastMatch
                    )
                }
            }
        }
    }

    private fun handleFileUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    parseCsvStream(inputStream)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Error loading file: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private suspend fun parseCsvStream(inputStream: InputStream) = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Loading barcodes...", Toast.LENGTH_SHORT).show()
        }

        val list = mutableListOf<Want>()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            val csvReader = CSVReaderBuilder(reader)
                .withCSVParser(
                    CSVParserBuilder()
                        .withSeparator(',')
                        .withQuoteChar('"')
                        .withEscapeChar('\\')
                        .build()
                ).build()

            var line: Array<String>?
            while (csvReader.readNext().also { line = it } != null) {
                val record = line!!
                if (record.size < 6) continue

                val rawCode = record[0].filter { it.isDigit() || it.isLetter() }
                if (!rawCode.all { it.isDigit() } || rawCode.length > 13) continue

                list += Want(
                    barcode = rawCode,
                    artist = record[1],
                    album = record[2],
                    wants = record[5]
                )
            }
        }

        withContext(Dispatchers.Main) {
            wantList.clear()
            wantList.addAll(list)
            fileLoaded = true
            Toast.makeText(
                this@MainActivity,
                "Loaded ${list.size} barcodes!",
                Toast.LENGTH_SHORT
            ).show()
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

        val revised = if (cleaned.length >= 3) cleaned.substring(1, cleaned.length - 1) else cleaned
        val match = wantList.firstOrNull { it.barcode.contains(revised) }

        if (match != null) {
            playSound(R.raw.success_sound)
            triggerVibration(1000)
        } else {
            playSound(R.raw.fail_sound)
            triggerVibration(100)
        }

        lastMatch = match
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
}

@Composable
fun MainScreen(
    modifier: Modifier,
    enabled: Boolean,
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
            enabled = enabled,
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
            enabled = enabled && barcodeText.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled && barcodeText.isNotEmpty()) Color(0xFFFABD08)
                else Color(0xFFCCCCCC)
            )
        ) {
            Text(
                "Submit",
                color = if (enabled && barcodeText.isNotEmpty()) Color(0xFF1C2C4D) else Color(
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
