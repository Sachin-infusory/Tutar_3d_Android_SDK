package com.infusory.threedrenderer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.infusory.lib3drenderer.Tutar
import com.infusory.lib3drenderer.containerview.ModelData
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var containerParent: FrameLayout

    private val availableModels = arrayOf(
        "CirculatorySystem.glb",
        "DNA.glb",
        "Solenoid.glb",
        "Vowels.glb"
    )

    private val basePath = "/storage/emulated/0/Download/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        containerParent = findViewById(R.id.container3D)

        Tutar.initialize(this) { success ->
            // SDK Initialized
        }

        val btn: Button = findViewById(R.id.btn)
        btn.setOnClickListener {
            showCustomModelDialog()
        }
    }

    private fun showCustomModelDialog() {
        // 1. Inflate the custom layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_selection, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.modelsContainer)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // 2. Dynamically add a "Cool" button for each model
        availableModels.forEach { fileName ->
            val itemLayout = TextView(this).apply {
                text = "  📦  ${fileName.substringBefore(".")}" // Add an emoji icon for style
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(40, 40, 40, 40)
                gravity = Gravity.CENTER_VERTICAL

                // Add a simple bottom border for separation
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background) // Standard ripple

                // Click Listener
                setOnClickListener {
                    val fullPath = File(basePath, fileName).absolutePath
                    val modelName = fileName.substringBeforeLast(".")
                    val modelData = ModelData(modelName, fileName, null)

                    onModelSelected(modelData, fullPath)
                    dialog.dismiss() // Close popup
                }
            }
            container.addView(itemLayout)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        // 3. Essential: Make the window transparent so our rounded corners show
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun onModelSelected(modelData: ModelData, modelPath: String) {
        // containerParent.removeAllViews() // Uncomment if you need to clear previous models
        Tutar.createContainer(
            context = this,
            modelData = modelData,
            modelPath = modelPath,
            parent = containerParent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Tutar.release()
    }
}