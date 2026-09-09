package com.enajid.apkbuilder.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.enajid.apkbuilder.ApkBuilderApp
import com.enajid.apkbuilder.ui.build.BuildStatusScreen
import com.enajid.apkbuilder.ui.components.ScreenLoading
import com.enajid.apkbuilder.ui.createproject.CreateProjectScreen
import com.enajid.apkbuilder.ui.editor.EditorScreen
import com.enajid.apkbuilder.ui.home.HomeScreen
import com.enajid.apkbuilder.ui.onboarding.SignInScreen
import com.enajid.apkbuilder.ui.onboarding.WelcomeScreen

object Routes {
    const val WELCOME = "welcome"
    const val SIGNIN = "signin"
    const val HOME = "home"
    const val CREATE = "create"
    const val EDITOR = "editor/{owner}/{repo}"
    const val BUILD = "build/{owner}/{repo}"

    fun editor(owner: String, repo: String) = "editor/$owner/$repo"
    fun build(owner: String, repo: String) = "build/$owner/$repo"
}

@Composable
fun ApkBuilderRoot() {
    val app = LocalContext.current.applicationContext as ApkBuilderApp
    // null = still reading DataStore, "" = signed out, anything else = signed in.
    val token by produceState<String?>(initialValue = null, app) {
        app.container.tokenStore.tokenFlow.collect { value = it }
    }
    when (token) {
        null -> ScreenLoading()
        "" -> UnauthenticatedApp()
        else -> AuthenticatedApp()
    }
}

@Composable
private fun UnauthenticatedApp() {
    val navController = rememberNavController()
    ApkBuilderNavHost(navController = navController, startDestination = Routes.WELCOME)
}

@Composable
private fun AuthenticatedApp() {
    val navController = rememberNavController()
    ApkBuilderNavHost(navController = navController, startDestination = Routes.HOME)
}

@Composable
private fun ApkBuilderNavHost(navController: NavHostController, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onContinue = { navController.navigate(Routes.SIGNIN) })
        }
        composable(Routes.SIGNIN) {
            SignInScreen(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onCreate = { navController.navigate(Routes.CREATE) },
                onOpen = { owner, repo -> navController.navigate(Routes.editor(owner, repo)) },
            )
        }
        composable(Routes.CREATE) {
            CreateProjectScreen(
                onCreated = { owner, repo ->
                    navController.navigate(Routes.editor(owner, repo)) {
                        popUpTo(Routes.CREATE) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
            ),
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()
            // Prompt handed over by the build screen's "Fix with AI" button
            // (set on this entry's savedStateHandle before popping back).
            var agentPrompt by remember {
                mutableStateOf(entry.savedStateHandle.remove<String>("agent_prompt"))
            }
            EditorScreen(
                owner = owner,
                repo = repo,
                onBack = { navController.popBackStack() },
                onBuild = { navController.navigate(Routes.build(owner, repo)) },
                initialAgentPrompt = agentPrompt,
            )
            LaunchedEffect(Unit) { agentPrompt = null }
        }
        composable(
            route = Routes.BUILD,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
            ),
        ) { entry ->
            val owner = entry.arguments?.getString("owner").orEmpty()
            val repo = entry.arguments?.getString("repo").orEmpty()
            BuildStatusScreen(
                owner = owner,
                repo = repo,
                onBack = { navController.popBackStack() },
                onEditCode = { navController.popBackStack() },
                onFixWithAi = { prompt ->
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set("agent_prompt", prompt)
                    navController.popBackStack()
                },
            )
        }
    }
}
