package tipz.viola.settings

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import androidx.preference.Preference
import tipz.viola.Application
import tipz.viola.R

open class MaterialPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private val settingsPreference: SettingsSharedPreference =
        (context.applicationContext as Application).settingsPreference
    private val mRequiredApi: Int

    init {
        // Get attrs
        val a = context.obtainStyledAttributes(attrs, R.styleable.MaterialSwitchPreference)
        mRequiredApi = a.getInteger(R.styleable.MaterialSwitchPreference_requiredApi, 1)
        a.recycle()

        // Check for required API
        if (mRequiredApi > Build.VERSION.SDK_INT) this.isEnabled = false
    }
}