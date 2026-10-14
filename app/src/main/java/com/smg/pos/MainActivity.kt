package com.smg.pos

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var altColor = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val button = findViewById<Button>(R.id.color_button)
        button.setOnClickListener {
            altColor = !altColor
            val color = if (altColor) R.color.button_alt else R.color.button_normal
            button.backgroundTintList = ContextCompat.getColorStateList(this, color)
        }
    }
}
