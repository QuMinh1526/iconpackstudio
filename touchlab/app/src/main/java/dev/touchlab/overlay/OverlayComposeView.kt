package dev.touchlab.overlay

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.touchlab.ui.TouchLabTheme

/**
 * View Compose dùng được trong WindowManager (overlay) — nơi không có Activity.
 *
 * Compose cần LifecycleOwner + SavedStateRegistryOwner + ViewModelStoreOwner; lớp này
 * cung cấp đủ ba thứ đó một cách tối thiểu.
 */
@SuppressLint("ViewConstructor")
class OverlayComposeView(
    context: Context,
    private val content: @Composable () -> Unit,
) : AbstractComposeView(context), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        // Overlay bị remove/add liên tục → giải phóng composition khi detach để không rò rỉ.
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onDetachedFromWindow()
    }

    @Composable
    override fun Content() {
        // Overlay không nằm trong Activity nên phải tự bọc MaterialTheme.
        TouchLabTheme { content() }
    }

    /** Gọi khi bỏ hẳn view khỏi WindowManager. */
    fun release() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
