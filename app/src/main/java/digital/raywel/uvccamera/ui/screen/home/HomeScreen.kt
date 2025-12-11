package digital.raywel.uvccamera.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import digital.raywel.uvccamera.ui.activity.CaptureState
import digital.raywel.uvccamera.ui.activity.MainViewModel
import digital.raywel.uvccamera.ui.component.CapturedImage
import digital.raywel.uvccamera.ui.component.CustomDialog
import timber.log.Timber

@Composable
fun HomeScreen(viewModel: MainViewModel) {

    val dialogState by viewModel.dialogState.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val isButtonEnabled = captureState !is CaptureState.Request

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (dialogState.show) {
                CustomDialog(
                    title = dialogState.title,
                    description = dialogState.description,
                    onDismiss = { viewModel.closeDialog() }
                )
            }

            // -------------------------
            // Preview Image (Top)
            // -------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (captureState) {
                    is CaptureState.Success -> {
                        val filePath = (captureState as CaptureState.Success).filePath
                        Timber.i("Set Image On $filePath")
                        CapturedImage(filePath)
                    }
                    is CaptureState.Error -> {
                        viewModel.openDialog(
                            "Something went wrong.",
                            (captureState as CaptureState.Error).message.takeUnless { it.isBlank() } ?: "Capture error."
                        )
                    }

                    else -> {
                        Text("No image captured yet", modifier = Modifier.padding(16.dp))
                    }
                }
            }


            Spacer(Modifier.height(24.dp))

            // -------------------------
            // Capture Button (Bottom)
            // -------------------------
            Button(
                onClick = { viewModel.requestCapture() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFEB3B),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(6.dp),
                enabled = isButtonEnabled
            ) {
                if (captureState is CaptureState.Request) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color(0xFFFFEB3B),
                    )
                } else {
                    Text("Capture Now")
                }
            }
        }
    }
}