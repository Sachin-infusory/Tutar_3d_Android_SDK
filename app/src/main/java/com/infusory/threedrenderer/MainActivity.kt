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

    // Track how many containers have been added so each one is offset
    private var containerCount = 0
    private val OFFSET_STEP_DP = 40 // Each new container is offset by this much

    private val availableModels = arrayOf(
        "BirdAndNest.glb",
        "OzoneDepletion.glb",
        "CirculatorySystem.glb",
        "DNA.glb",
        "Solenoid.glb",
        "Vowels.glb",
        "Rutherfords_experiment.glb",
        "skeleton.glb",
        "steam_engine.glb",
        "anatomy_of_a_flower.glb",
        "animal_cell.glb",
        "Brain.glb"
    )

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
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_selection, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.modelsContainer)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        availableModels.forEach { fileName ->
            val itemLayout = TextView(this).apply {
                text = "  📦  ${fileName.substringBefore(".")}"
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(40, 40, 40, 40)
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)

                setOnClickListener {
                    val fullPath = File("hh", fileName).absolutePath
                    val modelName = fileName.substringBeforeLast(".")
                    val modelData = ModelData(modelName, fileName, null)

                    onModelSelected(modelData, fullPath)
                    dialog.dismiss()
                }
            }
            container.addView(itemLayout)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun onModelSelected(modelData: ModelData, modelPath: String) {
        val container3D = Tutar.createContainer(
            context = this,
            modelData = modelData,
            modelPath = modelPath,
            parent = containerParent
        )

        // Offset each new container so they don't stack on top of each other.
        // This prevents all containers from occupying the same visual space
        // and receiving the same touch events.
        container3D?.let {
            val offsetPx = dpToPx(OFFSET_STEP_DP) * containerCount
            it.translationX = offsetPx.toFloat()
            it.translationY = offsetPx.toFloat()
            containerCount++

            // When a container is removed, decrement the count
            val originalRemoveCallback = it.onRemoveRequest
            it.onRemoveRequest = {
                containerCount--
                originalRemoveCallback?.invoke()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        Tutar.release()
    }
}
