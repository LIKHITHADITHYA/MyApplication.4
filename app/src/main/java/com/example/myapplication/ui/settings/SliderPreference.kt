package com.example.myapplication.ui.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.example.myapplication.R // Make sure this R class import is correct for your project structure
import com.google.android.material.slider.Slider

class SliderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0, // Default preference style from theme
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    private var currentValue: Float
    private val minValue: Float
    private val maxValue: Float
    private val stepSize: Float
    private val defaultValue: Float // Default value from custom attributes
    private val valueFormat: String

    private lateinit var valueTextView: TextView
    private lateinit var sliderView: Slider

    init {
        // This layout must contain a TextView with id 'pref_slider_value_text'
        // and a com.google.android.material.slider.Slider with id 'pref_slider'.
        widgetLayoutResource = R.layout.preference_widget_slider

        // These attributes must be defined in an attrs.xml file.
        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.SliderPreference,
            defStyleAttr,
            defStyleRes
        )
        try {
            minValue = typedArray.getFloat(R.styleable.SliderPreference_slider_minValue, 0f)
            maxValue = typedArray.getFloat(R.styleable.SliderPreference_slider_maxValue, 100f)
            stepSize = typedArray.getFloat(R.styleable.SliderPreference_slider_stepSize, 1f)
            // Default value for this preference (from custom attributes), fallback to minValue
            defaultValue =
                typedArray.getFloat(R.styleable.SliderPreference_slider_defaultValue, minValue)
            valueFormat =
                typedArray.getString(R.styleable.SliderPreference_slider_valueFormat) ?: "%.1f"
        } finally {
            typedArray.recycle()
        }
        // Initialize current value with the default from attributes before trying to load persisted value
        // or value from android:defaultValue in XML.
        currentValue = defaultValue
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        valueTextView = holder.findViewById(R.id.pref_slider_value_text) as TextView
        sliderView = holder.findViewById(R.id.pref_slider) as Slider

        sliderView.valueFrom = minValue
        sliderView.valueTo = maxValue
        sliderView.stepSize = stepSize
        sliderView.value = currentValue // Set the slider's position to the current value

        updateValueText(currentValue)

        sliderView.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                // Check if the listener allows the change and persist it
                if (callChangeListener(value)) {
                    persistFloatValue(value)
                } else {
                    // If change is rejected, revert slider to its current persisted value
                    // This might be needed if the listener rejects often
                    // sliderView.value = currentValue
                }
            }
        }

        sliderView.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                // Optional: Handle touch start
            }

            override fun onStopTrackingTouch(slider: Slider) {
                // Optional: Handle touch stop.
                // Value is typically already persisted by addOnChangeListener if fromUser.
                // If you only want to persist onStopTrackingTouch, move persistFloatValue here
                // and ensure to check callChangeListener.
            }
        })
    }

    private fun persistFloatValue(value: Float) {
        if (shouldPersist()) { // Checks if persistence is enabled
            persistFloat(value) // Persist the new float value
            currentValue = value  // Update the internal current value
            updateValueText(value)// Update the displayed text
            // notifyChanged() // Usually not needed unless summary or other view aspects change
        }
    }

    private fun updateValueText(value: Float) {
        // Ensure valueTextView has been initialized
        if (this::valueTextView.isInitialized) {
            valueTextView.text = String.format(valueFormat, value)
        }
    }

    override fun onSetInitialValue(defaultVal: Any?) {
        // Called when the preference is added or when android:defaultValue is specified in XML.
        // defaultVal is the value from android:defaultValue in the PreferenceScreen XML, if provided.
        // It can be null if not specified.
        val effectiveDefault = defaultVal as? Float ?: this.defaultValue

        // Load the persisted value; if not persisted, use the effectiveDefault.
        currentValue =
            if (shouldPersist()) getPersistedFloat(effectiveDefault) else effectiveDefault
        updateValueText(currentValue)
    }

    // Optional: Override if you need to provide a default value programmatically
    // when android:defaultValue is not set in XML and no custom attribute default is sufficient.
    // override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
    //     // This would be called if android:defaultValue is not specified in PreferenceScreen XML.
    //     // 'a' is the TypedArray for the PreferenceScreen XML attributes.
    //     // 'index' is the index of android:defaultValue.
    //     return a.getFloat(index, minValue) // Example: Default to minValue if nothing else is specified
    // }
}
