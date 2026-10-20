package org.jellyfin.mobile.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentManager
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.databinding.FragmentComposeBinding
import org.jellyfin.mobile.ui.screens.connect.ConnectScreen
import org.jellyfin.mobile.ui.utils.AppTheme
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ConnectFragment : Fragment() {
    private val mainViewModel: MainViewModel by activityViewModel()
    private var _viewBinding: FragmentComposeBinding? = null
    private val viewBinding get() = _viewBinding!!
    private val composeView: ComposeView get() = viewBinding.composeView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _viewBinding = FragmentComposeBinding.inflate(inflater, container, false)
        return composeView.apply { applyWindowInsetsAsMargins() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply window insets
        ViewCompat.requestApplyInsets(composeView)

        val encounteredConnectionError = arguments?.getBoolean(Constants.FRAGMENT_CONNECT_EXTRA_ERROR) == true

        // KinoLel: Liegt etwas darüber (Downloads, Player), nicht automatisch zur Weboberfläche wechseln – das würde eine
        // Offline-Wiedergabe unterbrechen, sobald das Internet zurückkommt.
        val oben = mutableStateOf(parentFragmentManager.backStackEntryCount == 0)
        val beobachter = FragmentManager.OnBackStackChangedListener {
            oben.value = parentFragmentManager.backStackEntryCount == 0
        }
        parentFragmentManager.addOnBackStackChangedListener(beobachter)
        viewLifecycleOwner.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, ereignis ->
                if (ereignis == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) parentFragmentManager.removeOnBackStackChangedListener(beobachter)
            },
        )

        composeView.setContent {
            AppTheme {
                ConnectScreen(
                    mainViewModel = mainViewModel,
                    showExternalConnectionError = encounteredConnectionError,
                    oben = oben.value,
                )
            }
        }
    }
}
