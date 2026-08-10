package com.prism.launcher.social

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prism.launcher.AppDatabase
import com.prism.launcher.PrismSettings
import com.prism.launcher.databinding.ActivitySocialComposeBinding
import com.prism.launcher.messaging.ImageGenManager
import com.prism.launcher.messaging.VisionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NebulaComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySocialComposeBinding
    private var selectedImageUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            binding.imagePreviewContainer.visibility = View.VISIBLE
            binding.imgPreview.setImageURI(uri)
        }
    }

    private var voice: com.prism.launcher.voice.VoiceInputController? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                voice?.start()
            } else {
                Toast.makeText(this, "Dictation needs microphone access.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onDestroy() {
        voice?.release()
        voice = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySocialComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }
        
        binding.btnAddImage.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            binding.imagePreviewContainer.visibility = View.GONE
        }

        binding.btnPost.setOnClickListener {
            performPost()
        }

        binding.btnGenerateImage.setOnClickListener {
            generateImageFromPrompt()
        }

        // No autoSubmit: a post is published to everyone and cannot be recalled, so a dictated
        // one lands in the composer to be read back — and there is an image attachment step
        // after it anyway.
        voice = com.prism.launcher.voice.VoiceInputController(
            micButton = binding.composeMicBtn,
            input = binding.composeInput
        ).apply {
            permissionRequester = {
                micPermission.launch(com.prism.launcher.voice.VoiceInputController.PERMISSION)
            }
        }

        // Load user avatar (mock for now, or from prefs)
        binding.userAvatar.setImageResource(android.R.drawable.ic_menu_gallery)
    }

    private fun generateImageFromPrompt() {
        val prompt = binding.composeInput.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Type what you want the image to show first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!PrismSettings.isLocalImageModelImported() && PrismSettings.getAiMode() != PrismSettings.AI_MODE_CLOUD) {
            Toast.makeText(this, "No image model loaded — import one from the Model Store first.", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnGenerateImage.isEnabled = false
        binding.imageGenProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val uri = try {
                ImageGenManager.generateImage(this@NebulaComposeActivity, prompt)
            } catch (e: Exception) {
                null
            }

            binding.imageGenProgress.visibility = View.GONE
            binding.btnGenerateImage.isEnabled = true

            if (uri != null) {
                selectedImageUri = uri
                binding.imagePreviewContainer.visibility = View.VISIBLE
                binding.imgPreview.setImageURI(uri)
            } else {
                Toast.makeText(this@NebulaComposeActivity, "Image generation failed. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performPost() {
        val content = binding.composeInput.text.toString().trim()
        if (content.isEmpty() && selectedImageUri == null) {
            Toast.makeText(this, "Can't post an empty thought.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnPost.isEnabled = false
        
        lifecycleScope.launch {
            val db = AppDatabase.get()
            
            // Analyze image for AI bots to "see" it
            var visionTags = ""
            selectedImageUri?.let { uri ->
                visionTags = withContext(Dispatchers.IO) {
                    VisionService.analyzeImage(this@NebulaComposeActivity, uri)
                }
            }

            val post = SocialPostEntity(
                postId = UUID.randomUUID().toString(),
                authorId = "user",
                authorName = "You",
                authorHandle = "@user",
                authorAvatarUrl = null,
                content = content,
                imageUrl = selectedImageUri?.toString(),
                isUserPost = true
            )

            withContext(Dispatchers.IO) {
                db.socialDao().insertPost(post)
                
                // Trigger AI interaction if visionTags are present
                if (visionTags.isNotEmpty() || content.isNotEmpty()) {
                    triggerBotReactions(post, visionTags)
                }
            }

            Toast.makeText(this@NebulaComposeActivity, "Posted to Mesh!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun triggerBotReactions(post: SocialPostEntity, visionTags: String) {
        // Find 1-2 random bots to comment
        val db = AppDatabase.get()
        val bots = db.socialDao().getAllBots()
        if (bots.isEmpty()) return
        
        val count = (1..2).random()
        repeat(count) {
            val bot = bots.random()
            val contextText = if (visionTags.isNotEmpty()) "The user posted an image. $visionTags" else ""
            val prompt = "You are ${bot.name} (@${bot.handle}). " +
                         "Comment on the user's post: '${post.content}'. $contextText " +
                         "Be conversational."
            
            // Trigger immediately for responsiveness
            com.prism.launcher.social.NebulaSocialManager.generateBotComment(this, bot, post, visionTags = visionTags)
        }
    }
}
