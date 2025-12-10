package digital.raywel.uvucamera.ui.activity

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {
    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState = _dialogState.asStateFlow()

    fun openDialog(title: String, description: String) {
        _dialogState.value = DialogState(
            show = true,
            title = title,
            description = description
        )
    }

    fun closeDialog() {
        _dialogState.value = _dialogState.value.copy(show = false)
    }

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState = _captureState.asStateFlow()

    fun requestCapture() {
        _captureState.value = CaptureState.Request
    }

    fun onCaptureSuccess(path: String) {
        _captureState.value = CaptureState.Success(path)
    }

    fun onCaptureError(msg: String) {
        _captureState.value = CaptureState.Error(msg)
    }
}

data class DialogState(
    val show: Boolean = false,
    val title: String = "",
    val description: String = ""
)

sealed class CaptureState {
    data object Idle : CaptureState()
    data object Request : CaptureState()
    data class Success(val filePath: String) : CaptureState()
    data class Error(val message: String) : CaptureState()
}
