package com.enajid.apkbuilder.data

import com.enajid.apkbuilder.BuildConfig

/**
 * GitHub OAuth configuration.
 *
 * Register your own OAuth app at https://github.com/settings/developers
 * (choose "OAuth App" and tick "Enable Device Flow"), then set the client ID
 * via the `GITHUB_CLIENT_ID` Gradle property when building. See README.md.
 */
object GitHubAuth {
    val CLIENT_ID: String = BuildConfig.GITHUB_CLIENT_ID

    /**
     * Least privilege for what the app does:
     *  - public_repo — create/modify/delete public repositories (the app's
     *    projects are public so GitHub Actions stays free)
     *  - workflow   — push the generated build.yml workflow files
     *  - delete_repo — the in-app "Delete project" action
     */
    const val SCOPES = "public_repo workflow delete_repo"

    const val DEFAULT_VERIFICATION_URL = "https://github.com/login/device"
}
