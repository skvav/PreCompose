package moe.tlaster.precompose.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import moe.tlaster.precompose.lifecycle.LocalLifecycleOwner
import moe.tlaster.precompose.lifecycle.currentLocalLifecycleOwner
import moe.tlaster.precompose.navigation.route.ComposeRoute
import moe.tlaster.precompose.navigation.route.FloatingRoute
import moe.tlaster.precompose.navigation.route.GroupRoute
import moe.tlaster.precompose.navigation.transition.NavTransition
import moe.tlaster.precompose.stateholder.LocalSavedStateHolder
import moe.tlaster.precompose.stateholder.LocalStateHolder
import moe.tlaster.precompose.stateholder.currentLocalSavedStateHolder
import moe.tlaster.precompose.stateholder.currentLocalStateHolder

/**
 * Provides in place in the Compose hierarchy for self-contained navigation to occur.
 *
 * Once this is called, any Composable within the given [RouteBuilder] can be navigated to from
 * the provided [RouteBuilder].
 *
 * The builder passed into this method is [remember]ed. This means that for this NavHost, the
 * contents of the builder cannot be changed.
 *
 * @param navigator the Navigator for this host
 * @param initialRoute the route for the start destination
 * @param navTransition navigation transition for the scenes in this [NavHost]
 * @param swipeProperties properties of swipe back navigation
 * @param builder the builder used to construct the graph
 */
@OptIn(
    ExperimentalTransitionApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun NavHost(
    navigator: Navigator,
    initialRoute: String,
    modifier: Modifier = Modifier,
    navTransition: NavTransition = remember { NavTransition() },
    swipeProperties: SwipeProperties? = null,
    builder: RouteBuilder.() -> Unit,
) {
    val lifecycleOwner = currentLocalLifecycleOwner
    val stateHolder = currentLocalStateHolder
    val savedStateHolder = currentLocalSavedStateHolder
    val composeStateHolder = rememberSaveableStateHolder()

    // true for assuming that lifecycleOwner, stateHolder and composeStateHolder are not changing during the lifetime of the NavHost
    LaunchedEffect(true) {
        navigator.init(
            stateHolder = stateHolder,
            savedStateHolder = savedStateHolder,
            lifecycleOwner = lifecycleOwner,
        )
    }

    LaunchedEffect(builder, initialRoute) {
        navigator.setRouteGraph(
            RouteBuilder(initialRoute).apply(builder).build(),
        )
    }

    val canGoBack by navigator.stackManager.canGoBack.collectAsState(false)

    val currentEntry by navigator.stackManager.currentBackStackEntry.collectAsState(null)

    LaunchedEffect(currentEntry, composeStateHolder) {
        val entry = currentEntry
        val route = entry?.route
        if (route is ComposeRoute || route is GroupRoute && route.initialRoute is ComposeRoute) {
            val closable = entry.uiClosable
            if (closable == null || closable !is ComposeUiClosable || closable.composeSaveableStateHolder != composeStateHolder) {
                entry.uiClosable = ComposeUiClosable(composeStateHolder)
            }
        }
    }

    val currentSceneEntry by navigator.stackManager
        .currentSceneBackStackEntry.collectAsState(null)
    val prevSceneEntry by navigator.stackManager
        .prevSceneBackStackEntry.collectAsState(null)
    val currentFloatingEntry by navigator.stackManager
        .currentFloatingBackStackEntry.collectAsState(null)
    var progress by remember { mutableFloatStateOf(0f) }
    var inPredictiveBack by remember { mutableStateOf(false) }
    PredictiveBackHandler(canGoBack) { backEvent ->
        inPredictiveBack = true
        progress = 0f
        try {
            backEvent.collect {
                progress = it
            }
            if (progress != 1f) {
                // play the animation to the end
                progress = 1f
            }
        } catch (e: CancellationException) {
            inPredictiveBack = false
        }
    }
    if (currentSceneEntry != null || currentFloatingEntry != null) {
        BoxWithConstraints(modifier) {
            currentSceneEntry?.let { sceneEntry ->
                val actualSwipeProperties = sceneEntry.swipeProperties ?: swipeProperties
                val state = if (actualSwipeProperties != null) {
                    val density = LocalDensity.current
                    val width = constraints.maxWidth.toFloat()
                    val decayAnimationSpec = rememberSplineBasedDecay<Float>()
                    remember(actualSwipeProperties) {
                        AnchoredDraggableState(
                            initialValue = DragAnchors.Start,
                            anchors = DraggableAnchors {
                                DragAnchors.Start at 0f
                                DragAnchors.End at width
                            },
                            positionalThreshold = actualSwipeProperties.positionalThreshold,
                            velocityThreshold = { actualSwipeProperties.velocityThreshold.invoke(density) },
                            snapAnimationSpec = tween(),
                            decayAnimationSpec = decayAnimationSpec,
                        )
                    }.also { state ->
                        LaunchedEffect(
                            state.currentValue,
                            state.isAnimationRunning,
                        ) {
                            if (state.currentValue == DragAnchors.End && !state.isAnimationRunning) {
                                // play the animation to the end
                                progress = 1f
                            }
                        }
                        val stateProgress = state.progress(DragAnchors.Start, DragAnchors.End)
                        LaunchedEffect(stateProgress) {
                            if (stateProgress != 1f) {
                                inPredictiveBack = stateProgress > 0f
                                progress = stateProgress
                            } else if (state.currentValue != DragAnchors.End && inPredictiveBack) {
                                // reset the state to the initial value
                                progress = -1f
                            }
                        }
                    }
                } else {
                    null
                }
                val showPrev by remember(inPredictiveBack, prevSceneEntry, currentEntry) {
                    derivedStateOf {
                        inPredictiveBack &&
                            progress != 0f &&
                            prevSceneEntry != null &&
                            currentEntry?.route !is FloatingRoute
                    }
                }
                val transition = if (showPrev) {
                    val transitionState by remember(sceneEntry) {
                        mutableStateOf(SeekableTransitionState(sceneEntry))
                    }
                    LaunchedEffect(progress) {
                        if (progress == 1f) {
                            // play the animation to the end
                            transitionState.animateTo(prevSceneEntry!!)
                            inPredictiveBack = false
                            navigator.goBack()
                            progress = 0f
                            state?.snapTo(DragAnchors.Start)
                        } else if (progress >= 0) {
                            transitionState.seekTo(progress, targetState = prevSceneEntry!!)
                        } else if (progress == -1f) {
                            // reset the state to the initial value
                            transitionState.seekTo(0f, targetState = prevSceneEntry!!)
                            inPredictiveBack = false
                            progress = 0f
                        }
                    }
                    rememberTransition(transitionState, label = "entry")
                } else {
                    updateTransition(sceneEntry, label = "entry")
                }
                val transitionSpec: AnimatedContentTransitionScope<BackStackEntry>.() -> ContentTransform =
                    {
                        val actualTransaction = run {
                            if (navigator.stackManager.contains(initialState) && !showPrev) targetState else initialState
                        }.navTransition ?: navTransition
                        if (!navigator.stackManager.contains(initialState) || showPrev) {
                            ContentTransform(
                                targetContentEnter = actualTransaction.resumeTransition,
                                initialContentExit = actualTransaction.destroyTransition,
                                targetContentZIndex = actualTransaction.enterTargetContentZIndex,
                                // sizeTransform will cause the content to be resized
                                // when the transition is running with swipe back
                                // I have no idea why
                                // And it cost me weeks to figure it out :(
                                sizeTransform = null,
                            )
                        } else {
                            ContentTransform(
                                targetContentEnter = actualTransaction.createTransition,
                                initialContentExit = actualTransaction.pauseTransition,
                                targetContentZIndex = actualTransaction.exitTargetContentZIndex,
                                sizeTransform = null,
                            )
                        }
                    }
                transition.AnimatedContent(
                    transitionSpec = transitionSpec,
                    contentKey = { it.stateId },
                ) { entry ->
                    NavHostContent(composeStateHolder, entry)
                }
                if (state != null && actualSwipeProperties != null) {
                    DragSlider(
                        state = state,
                        enabled = prevSceneEntry != null,
                        spaceToSwipe = actualSwipeProperties.spaceToSwipe,
                    )
                }
            }
            currentFloatingEntry?.let {
                AnimatedContent(
                    it,
                    contentKey = { it.stateId },
                ) { entry ->
                    NavHostContent(composeStateHolder, entry)
                }
            }
        }
    }
}

@Composable
private fun AnimatedContentScope.NavHostContent(
    stateHolder: SaveableStateHolder,
    entry: BackStackEntry,
) {
    stateHolder.SaveableStateProvider(entry.stateId) {
        CompositionLocalProvider(
            LocalStateHolder provides entry.stateHolder,
            LocalSavedStateHolder provides entry.savedStateHolder,
            LocalLifecycleOwner provides entry,
            content = {
                entry.ComposeContent(this@NavHostContent)
            },
        )
    }
    DisposableEffect(entry) {
        entry.active()
        onDispose {
            entry.inActive()
        }
    }
}

private fun GroupRoute.composeRoute(): ComposeRoute? {
    return if (initialRoute is GroupRoute) {
        initialRoute.composeRoute()
    } else {
        initialRoute as? ComposeRoute
    }
}

@Composable
private fun BackStackEntry.ComposeContent(animatedContentScope: AnimatedContentScope) {
    if (route is GroupRoute) {
        (route as GroupRoute).composeRoute()
    } else {
        route as? ComposeRoute
    }?.content?.invoke(animatedContentScope, this)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DragSlider(
    state: AnchoredDraggableState<DragAnchors>,
    enabled: Boolean = true,
    spaceToSwipe: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(spaceToSwipe)
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                reverseDirection = isRtl,
            ),
    )
}

private enum class DragAnchors {
    Start,
    End,
}
