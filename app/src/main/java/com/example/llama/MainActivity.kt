package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        toolbar = findViewById(R.id.toolbar)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)

        // Toolbar navigation icon opens file picker to select custom model
        toolbar.setNavigationOnClickListener {
            getContent.launch(arrayOf("*/*"))
        }

        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        // FAB action
        userActionFab.setOnClickListener {
            if (isModelReady) {
                handleUserInput()
            } else {
                checkPermissionsAndScanModels()
            }
        }

        // Auto-scan for models on startup
        checkPermissionsAndScanModels()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Check for bundled model in assets first, then scan external directory
     */
    private fun checkPermissionsAndScanModels() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bundledModel = loadBundledModel()
            withContext(Dispatchers.Main) {
                if (bundledModel != null) {
                    loadModelFromFile(bundledModel)
                } else {
                    scanModelsDirectory()
                }
            }
        }
    }

    /**
     * Copy model from assets to internal storage if found.
     * Returns the File if a model was bundled, null otherwise.
     */
    private fun loadBundledModel(): File? {
        val assetList = assets.list("") ?: return null
        val ggufAsset = assetList.firstOrNull {
            it.endsWith(FILE_EXTENSION_GGUF, ignoreCase = true)
        } ?: return null

        val destFile = File(ensureModelsDirectory(), ggufAsset)
        if (destFile.exists()) {
            Log.i(TAG, "Bundled model already copied: ${destFile.absolutePath}")
            return destFile
        }

        Log.i(TAG, "Copying bundled model from assets: $ggufAsset")
        return try {
            assets.open(ggufAsset).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Bundled model copied to: ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying bundled model", e)
            null
        }
    }

    /**
     * Scan the models directory for .gguf files
     */
    private fun scanModelsDirectory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val modelsDir = getModelsDirectory()
            Log.i(TAG, "Scanning models directory: ${modelsDir.absolutePath}")

            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
                Log.i(TAG, "Created models directory")
            }

            val ggufFiles = modelsDir.listFiles { file ->
                file.isFile && file.name.endsWith(FILE_EXTENSION_GGUF, ignoreCase = true)
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            withContext(Dispatchers.Main) {
                when {
                    ggufFiles.size == 1 -> {
                        loadModelFromFile(ggufFiles.first())
                    }
                    else -> {
                        showModelSelectionDialog(ggufFiles)
                    }
                }
            }
        }
    }

    /**
     * Show dialog to select from multiple models
     */
    private fun showModelSelectionDialog(models: List<File>) {
        val modelNames = models.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Seleccionar Modelo GGUF")
            .setItems(modelNames) { _, which ->
                loadModelFromFile(models[which])
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Buscar archivo") { _, _ ->
                getContent.launch(arrayOf("*/*"))
            }
            .show()
    }

    /**
     * Load a model directly from a file in the models directory
     */
    private fun loadModelFromFile(file: File) {
        Log.i(TAG, "Loading model from file: ${file.absolutePath}")
        userActionFab.isEnabled = false
        userInputEt.hint = "Cargando modelo..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Parsing GGUF metadata from ${file.absolutePath}")
                val metadata = file.inputStream().use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                }

                if (metadata != null) {
                    Log.i(TAG, "GGUF parsed: \n$metadata")

                    val modelName = file.name
                    loadModel(modelName, file)

                    Log.i(TAG, "Setting system prompt...")
                    engine.setSystemPrompt(SYSTEM_PROMPT)

                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        toolbar.title = "AI Chat"
                        toolbar.subtitle = modelName
                        userInputEt.hint = "Escribe un mensaje..."
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                        userActionFab.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                resources.getColor(R.color.primary, theme)
                            )
                        userActionFab.isEnabled = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Error leyendo metadatos del modelo", Toast.LENGTH_SHORT).show()
                        userActionFab.isEnabled = true
                        userInputEt.hint = "Primero selecciona un modelo GGUF"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    userActionFab.isEnabled = true
                    userInputEt.hint = "Primero selecciona un modelo GGUF"
                }
            }
        }
    }

    /**
     * Get the models directory (accessible via file manager without permissions).
     * Path: /storage/emulated/0/Android/data/com.example.llama/files/models/
     */
    private fun getModelsDirectory(): File {
        val externalDir = getExternalFilesDir(null) ?: filesDir
        return File(externalDir, DIRECTORY_MODELS)
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        // Update UI states
        userActionFab.isEnabled = false
        userInputEt.hint = "Cargando modelo..."

        lifecycleScope.launch(Dispatchers.IO) {
            // Parse GGUF metadata
            Log.i(TAG, "Parsing GGUF metadata...")
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                Log.i(TAG, "GGUF parsed: \n$metadata")

                // Ensure the model file is available
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModel(modelName, modelFile)

                    // Set the system prompt for Gandria
                    Log.i(TAG, "Setting system prompt...")
                    engine.setSystemPrompt(SYSTEM_PROMPT)

                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        toolbar.title = "AI Chat"
                        toolbar.subtitle = modelName
                        userInputEt.hint = "Escribe un mensaje..."
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                        userActionFab.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                resources.getColor(R.color.primary, theme)
                            )
                        userActionFab.isEnabled = true
                    }
                }
            }
        }
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copiando archivo..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Cargando modelo..."
            }
            engine.loadModel(modelFile.path)
        }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "El mensaje está vacío!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Ejecutando benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the internal `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3

        private const val SYSTEM_PROMPT = """Eres un asistente llamado Gandria. Responde solo en español, de forma breve y clara. No repitas frases ni ideas. Cada oración debe aportar información nueva."""
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
