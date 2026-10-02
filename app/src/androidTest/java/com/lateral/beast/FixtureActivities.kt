package com.lateral.beast
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
open class FixtureOneActivity : Activity() {
 override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(TextView(this).apply { text = "Hosted fixture" }) }
}
class FixtureTwoActivity : FixtureOneActivity()
