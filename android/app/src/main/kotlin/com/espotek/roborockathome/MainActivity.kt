package com.espotek.roborockathome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.espotek.roborockathome.ui.shell.App
import com.espotek.roborockathome.ui.shell.AppModel

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels { AppModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(model) }
    }
}
